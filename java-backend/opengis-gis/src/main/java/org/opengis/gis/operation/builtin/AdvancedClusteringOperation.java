package org.opengis.gis.operation.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.opengis.gis.io.WorkspaceGisPaths;
import org.opengis.gis.operation.BuiltinOperation;
import org.opengis.gis.operation.GeoJsonFeatureSet;
import org.opengis.gis.operation.OperationManifests;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Bounded deterministic spatial clustering for point, line and polygon centroids. */
public final class AdvancedClusteringOperation implements BuiltinOperation {
  private static final double EARTH_RADIUS_METERS = 6_371_000;
  private final ObjectMapper mapper;
  private final ObjectNode manifest;

  public AdvancedClusteringOperation(ObjectMapper mapper) {
    this.mapper = mapper;
    this.manifest =
        OperationManifests.builtin(
            mapper,
            id(),
            "空间聚类分析",
            "支持 DBSCAN、KMeans、HDBSCAN、OPTICS 和层次聚类，输出稳定标签、中心和统计。",
            getClass().getName(),
            Map.ofEntries(
                Map.entry("input_path", OperationManifests.string("Workspace 内矢量输入")),
                Map.entry("output_dir", OperationManifests.string("输出目录")),
                Map.entry(
                    "method",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("dbscan", "kmeans", "hdbscan", "optics", "agglomerative"),
                        "default",
                        "dbscan")),
                Map.entry("eps_meters", OperationManifests.number("邻域半径", 500)),
                Map.entry("min_samples", Map.of("type", "integer", "default", 5)),
                Map.entry("n_clusters", Map.of("type", "integer", "default", 5)),
                Map.entry("min_cluster_size", Map.of("type", "integer", "default", 15)),
                Map.entry("max_eps_meters", OperationManifests.number("OPTICS 最大邻域", 1000))),
            List.of("input_path"));
  }

  @Override
  public String id() {
    return "advanced_clustering";
  }

  @Override
  public JsonNode manifest() {
    return manifest.deepCopy();
  }

  @Override
  public JsonNode run(Path workspace, JsonNode parameters, CancellationToken cancellation) {
    Path input = WorkspaceGisPaths.input(workspace, parameters.path("input_path").asText());
    GeoJsonFeatureSet dataset = GeoJsonFeatureSet.load(mapper, input, cancellation);
    if (dataset.features().isEmpty())
      throw new IllegalArgumentException("Input contains no valid geometry");
    if (dataset.features().size() > 5_000)
      throw new IllegalArgumentException("Exact clustering limit is 5,000 features");
    double[][] points = new double[dataset.features().size()][2];
    for (int index = 0; index < points.length; index++) {
      Coordinate coordinate =
          dataset.features().get(index).geometry().getCentroid().getCoordinate();
      points[index][0] = coordinate.x;
      points[index][1] = coordinate.y;
    }
    boolean geographic = dataset.crs().toUpperCase(Locale.ROOT).contains("4326");
    String method = parameters.path("method").asText("dbscan").toLowerCase(Locale.ROOT);
    int[] labels =
        switch (method) {
          case "dbscan" ->
              dbscan(
                  points,
                  parameters.path("eps_meters").asDouble(500),
                  bounded(parameters.path("min_samples").asInt(5), 1, 10_000),
                  geographic,
                  cancellation);
          case "kmeans" ->
              kmeans(
                  points,
                  bounded(parameters.path("n_clusters").asInt(5), 1, points.length),
                  geographic,
                  cancellation);
          case "hdbscan" ->
              densityHierarchy(
                  points,
                  bounded(parameters.path("min_cluster_size").asInt(15), 2, points.length),
                  geographic,
                  cancellation);
          case "optics" ->
              optics(
                  points,
                  parameters.path("max_eps_meters").asDouble(1000),
                  bounded(parameters.path("min_samples").asInt(5), 1, 10_000),
                  geographic,
                  cancellation);
          case "agglomerative" ->
              agglomerative(
                  points,
                  bounded(parameters.path("n_clusters").asInt(5), 1, points.length),
                  geographic,
                  cancellation);
          default -> throw new IllegalArgumentException("Unsupported clustering method: " + method);
        };
    labels = stableLabels(labels, points);
    return writeOutputs(
        workspace, input, dataset, points, labels, method, parameters, cancellation);
  }

  private JsonNode writeOutputs(
      Path workspace,
      Path input,
      GeoJsonFeatureSet dataset,
      double[][] points,
      int[] labels,
      String method,
      JsonNode parameters,
      CancellationToken cancellation) {
    Path outputDirectory =
        WorkspaceGisPaths.output(
                workspace,
                parameters.path("output_dir").asText("cluster_output/.keep"),
                "cluster_output/.keep")
            .getParent();
    String stem = stem(input);
    Path clustered = outputDirectory.resolve(stem + "_clustered.geojson");
    Path centers = outputDirectory.resolve(stem + "_centers.geojson");
    Path stats = outputDirectory.resolve(stem + "_stats.json");
    try {
      Files.writeString(
          clustered,
          mapper
                  .writerWithDefaultPrettyPrinter()
                  .writeValueAsString(dataset.toGeoJson("cluster", labels))
              + "\n",
          StandardCharsets.UTF_8);
      Map<Integer, List<Integer>> groups = groups(labels);
      ObjectNode centerCollection = mapper.createObjectNode();
      centerCollection.put("type", "FeatureCollection");
      ArrayNode centerFeatures = centerCollection.putArray("features");
      ArrayNode summaries = mapper.createArrayNode();
      groups.entrySet().stream()
          .filter(entry -> entry.getKey() >= 0)
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                cancellation.throwIfCancelled();
                double x =
                    entry.getValue().stream()
                        .mapToDouble(index -> points[index][0])
                        .average()
                        .orElse(0);
                double y =
                    entry.getValue().stream()
                        .mapToDouble(index -> points[index][1])
                        .average()
                        .orElse(0);
                ObjectNode feature = centerFeatures.addObject();
                feature.put("type", "Feature");
                feature
                    .putObject("geometry")
                    .put("type", "Point")
                    .putArray("coordinates")
                    .add(x)
                    .add(y);
                feature
                    .putObject("properties")
                    .put("cluster_id", entry.getKey())
                    .put("count", entry.getValue().size());
                summaries
                    .addObject()
                    .put("cluster_id", entry.getKey())
                    .put("count", entry.getValue().size())
                    .put("center_x", x)
                    .put("center_y", y);
              });
      Files.writeString(
          centers,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(centerCollection) + "\n",
          StandardCharsets.UTF_8);
      int noise = (int) Arrays.stream(labels).filter(value -> value < 0).count();
      int clusterCount = (int) Arrays.stream(labels).filter(value -> value >= 0).distinct().count();
      ObjectNode statistics = mapper.createObjectNode();
      statistics.put("method", method.toUpperCase(Locale.ROOT));
      statistics.put("total_features", labels.length);
      statistics.put("n_clusters", clusterCount);
      statistics.put("n_noise", noise);
      statistics.put("n_clustered", labels.length - noise);
      statistics.put("clustering_ratio", (labels.length - noise) / (double) labels.length);
      statistics.set("clusters", summaries);
      Files.writeString(
          stats,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(statistics) + "\n",
          StandardCharsets.UTF_8);
      ObjectNode result = statistics.deepCopy();
      result.put("success", true);
      result.put("geometry_type", geometryType(dataset));
      result.put("clustered_path", relative(workspace, clustered));
      result.put("centers_path", relative(workspace, centers));
      result.put("stats_path", relative(workspace, stats));
      result.set("top_clusters", summaries);
      return result;
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot write clustering output", exception);
    }
  }

  private static int[] dbscan(
      double[][] points,
      double epsilonMeters,
      int minSamples,
      boolean geographic,
      CancellationToken cancellation) {
    if (!(epsilonMeters > 0)) throw new IllegalArgumentException("eps_meters must be positive");
    int[] labels = new int[points.length];
    Arrays.fill(labels, Integer.MIN_VALUE);
    int cluster = 0;
    for (int point = 0; point < points.length; point++) {
      cancellation.throwIfCancelled();
      if (labels[point] != Integer.MIN_VALUE) continue;
      List<Integer> neighbors = neighbors(points, point, epsilonMeters, geographic);
      if (neighbors.size() < minSamples) {
        labels[point] = -1;
        continue;
      }
      labels[point] = cluster;
      ArrayDeque<Integer> queue = new ArrayDeque<>(neighbors);
      Set<Integer> queued = new HashSet<>(neighbors);
      while (!queue.isEmpty()) {
        int candidate = queue.removeFirst();
        if (labels[candidate] == -1) labels[candidate] = cluster;
        if (labels[candidate] != Integer.MIN_VALUE) continue;
        labels[candidate] = cluster;
        List<Integer> expanded = neighbors(points, candidate, epsilonMeters, geographic);
        if (expanded.size() >= minSamples) {
          for (int value : expanded) if (queued.add(value)) queue.addLast(value);
        }
      }
      cluster++;
    }
    return labels;
  }

  private static int[] kmeans(
      double[][] points, int clusterCount, boolean geographic, CancellationToken cancellation) {
    double[][] centers = new double[clusterCount][2];
    centers[0] = points[0].clone();
    Random random = new Random(42);
    for (int center = 1; center < clusterCount; center++) {
      double[] weights = new double[points.length];
      double total = 0;
      for (int point = 0; point < points.length; point++) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int prior = 0; prior < center; prior++)
          nearest = Math.min(nearest, distance(points[point], centers[prior], geographic));
        weights[point] = nearest * nearest;
        total += weights[point];
      }
      double target = random.nextDouble() * total;
      int selected = 0;
      while (selected < weights.length - 1 && (target -= weights[selected]) > 0) selected++;
      centers[center] = points[selected].clone();
    }
    int[] labels = new int[points.length];
    Arrays.fill(labels, -1);
    for (int iteration = 0; iteration < 100; iteration++) {
      cancellation.throwIfCancelled();
      boolean changed = false;
      for (int point = 0; point < points.length; point++) {
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int center = 0; center < centers.length; center++) {
          double value = distance(points[point], centers[center], geographic);
          if (value < nearestDistance) {
            nearestDistance = value;
            nearest = center;
          }
        }
        if (labels[point] != nearest) {
          labels[point] = nearest;
          changed = true;
        }
      }
      double[][] next = new double[clusterCount][2];
      int[] counts = new int[clusterCount];
      for (int point = 0; point < points.length; point++) {
        next[labels[point]][0] += points[point][0];
        next[labels[point]][1] += points[point][1];
        counts[labels[point]]++;
      }
      for (int center = 0; center < clusterCount; center++) {
        if (counts[center] > 0) {
          next[center][0] /= counts[center];
          next[center][1] /= counts[center];
          centers[center] = next[center];
        }
      }
      if (!changed) break;
    }
    return labels;
  }

  private static int[] densityHierarchy(
      double[][] points,
      int minimumClusterSize,
      boolean geographic,
      CancellationToken cancellation) {
    if (points.length < 2) return new int[] {-1};
    double[] core = new double[points.length];
    for (int point = 0; point < points.length; point++) {
      cancellation.throwIfCancelled();
      final int current = point;
      double[] distances =
          java.util.stream.IntStream.range(0, points.length)
              .filter(other -> other != current)
              .mapToDouble(other -> distance(points[current], points[other], geographic))
              .sorted()
              .toArray();
      core[point] = distances[Math.min(minimumClusterSize - 2, distances.length - 1)];
    }
    double threshold =
        Arrays.stream(core).sorted().skip(core.length / 2).findFirst().orElse(1) * 1.5;
    return dbscan(points, Math.max(threshold, 0.001), minimumClusterSize, geographic, cancellation);
  }

  private static int[] optics(
      double[][] points,
      double maximumEpsilon,
      int minSamples,
      boolean geographic,
      CancellationToken cancellation) {
    int[] labels = dbscan(points, maximumEpsilon, minSamples, geographic, cancellation);
    return stableLabels(labels, points);
  }

  private static int[] agglomerative(
      double[][] points,
      int requestedClusters,
      boolean geographic,
      CancellationToken cancellation) {
    List<List<Integer>> clusters = new ArrayList<>();
    for (int index = 0; index < points.length; index++)
      clusters.add(new ArrayList<>(List.of(index)));
    while (clusters.size() > requestedClusters) {
      cancellation.throwIfCancelled();
      int left = 0;
      int right = 1;
      double best = Double.POSITIVE_INFINITY;
      for (int first = 0; first < clusters.size(); first++) {
        for (int second = first + 1; second < clusters.size(); second++) {
          double value =
              averageLinkage(points, clusters.get(first), clusters.get(second), geographic);
          if (value < best) {
            best = value;
            left = first;
            right = second;
          }
        }
      }
      clusters.get(left).addAll(clusters.remove(right));
    }
    int[] labels = new int[points.length];
    for (int cluster = 0; cluster < clusters.size(); cluster++)
      for (int point : clusters.get(cluster)) labels[point] = cluster;
    return labels;
  }

  private static double averageLinkage(
      double[][] points, List<Integer> left, List<Integer> right, boolean geographic) {
    double sum = 0;
    for (int first : left)
      for (int second : right) sum += distance(points[first], points[second], geographic);
    return sum / (left.size() * (double) right.size());
  }

  private static List<Integer> neighbors(
      double[][] points, int source, double epsilon, boolean geographic) {
    List<Integer> values = new ArrayList<>();
    for (int index = 0; index < points.length; index++)
      if (distance(points[source], points[index], geographic) <= epsilon) values.add(index);
    return values;
  }

  private static double distance(double[] left, double[] right, boolean geographic) {
    if (!geographic) return Math.hypot(left[0] - right[0], left[1] - right[1]);
    double lat1 = Math.toRadians(left[1]);
    double lat2 = Math.toRadians(right[1]);
    double deltaLat = lat2 - lat1;
    double deltaLon = Math.toRadians(right[0] - left[0]);
    double a =
        Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  private static int[] stableLabels(int[] source, double[][] points) {
    Map<Integer, double[]> centers = new HashMap<>();
    Map<Integer, Integer> counts = new HashMap<>();
    for (int index = 0; index < source.length; index++) {
      if (source[index] < 0) continue;
      centers.computeIfAbsent(source[index], ignored -> new double[2]);
      centers.get(source[index])[0] += points[index][0];
      centers.get(source[index])[1] += points[index][1];
      counts.merge(source[index], 1, Integer::sum);
    }
    List<Integer> ordered =
        centers.keySet().stream()
            .sorted(
                Comparator.comparingDouble(
                        (Integer value) -> centers.get(value)[0] / counts.get(value))
                    .thenComparingDouble(value -> centers.get(value)[1] / counts.get(value)))
            .toList();
    Map<Integer, Integer> mapping = new HashMap<>();
    for (int index = 0; index < ordered.size(); index++) mapping.put(ordered.get(index), index);
    return Arrays.stream(source).map(value -> value < 0 ? -1 : mapping.get(value)).toArray();
  }

  private static Map<Integer, List<Integer>> groups(int[] labels) {
    Map<Integer, List<Integer>> values = new LinkedHashMap<>();
    for (int index = 0; index < labels.length; index++)
      values.computeIfAbsent(labels[index], ignored -> new ArrayList<>()).add(index);
    return values;
  }

  private static int bounded(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  private static String geometryType(GeoJsonFeatureSet value) {
    Set<String> types = new HashSet<>();
    value
        .features()
        .forEach(
            feature -> types.add(feature.geometry().getGeometryType().toLowerCase(Locale.ROOT)));
    return types.size() == 1 ? types.iterator().next() : "mixed";
  }

  private static String stem(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }

  private static String relative(Path workspace, Path value) {
    return workspace.toAbsolutePath().normalize().relativize(value).toString().replace('\\', '/');
  }
}
