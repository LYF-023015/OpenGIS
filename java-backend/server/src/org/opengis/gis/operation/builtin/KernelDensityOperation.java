/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.operation.builtin;

import java.awt.Point;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.opengis.core.concurrent.CancellationSignal;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.io.WorkspaceGisPaths;
import org.opengis.gis.operation.BuiltinOperation;
import org.opengis.gis.operation.GeoJsonFeatureSet;
import org.opengis.gis.operation.OperationManifests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Bounded primitive-array kernel density with GeoTIFF and optional contour products. */
public final class KernelDensityOperation implements BuiltinOperation {
  private static final List<String> KERNELS =
      List.of("gaussian", "epanechnikov", "uniform", "triangular", "quartic", "cosine");
  private final ObjectMapper mapper;
  private final CrsService crs = new CrsService();
  private final ObjectNode manifest;

  public KernelDensityOperation(ObjectMapper mapper) {
    this.mapper = mapper;
    this.manifest =
        OperationManifests.builtin(
            mapper,
            id(),
            "核密度估计",
            "生成有界密度网格、GeoTIFF、等值线和等值面。",
            getClass().getName(),
            Map.ofEntries(
                Map.entry("input_path", OperationManifests.string("Workspace 内矢量输入")),
                Map.entry("output_dir", OperationManifests.string("输出目录")),
                Map.entry("bandwidth_meters", OperationManifests.number("带宽（米）", 1000)),
                Map.entry("cell_size_meters", OperationManifests.number("网格大小（米）", 250)),
                Map.entry(
                    "kernel", Map.of("type", "string", "enum", KERNELS, "default", "gaussian")),
                Map.entry("weight_field", OperationManifests.string("可选权重字段")),
                Map.entry("normalize", Map.of("type", "boolean", "default", false)),
                Map.entry("output_contours", Map.of("type", "boolean", "default", false)),
                Map.entry("output_polygons", Map.of("type", "boolean", "default", false)),
                Map.entry("n_contours", Map.of("type", "integer", "default", 10)),
                Map.entry("max_grid_cells", Map.of("type", "integer", "default", 1_000_000))),
            List.of("input_path"));
  }

  @Override
  public String id() {
    return "kernel_density";
  }

  @Override
  public JsonNode manifest() {
    return manifest.deepCopy();
  }

  @Override
  public JsonNode run(Path workspace, JsonNode parameters, CancellationSignal cancellation) {
    Path input = WorkspaceGisPaths.input(workspace, parameters.path("input_path").asString());
    GeoJsonFeatureSet dataset = GeoJsonFeatureSet.load(mapper, input, cancellation);
    if (dataset.features().isEmpty())
      throw new IllegalArgumentException("Input contains no valid geometry");
    if (dataset.features().size() > 50_000)
      throw new IllegalArgumentException("KDE input limit is 50,000 features");
    String outputCrs = dataset.crs();
    boolean project = outputCrs.toUpperCase(Locale.ROOT).contains("4326");
    if (project) outputCrs = "EPSG:3857";
    double[][] points = new double[dataset.features().size()][2];
    double[] weights = new double[points.length];
    String weightField = parameters.path("weight_field").asString("");
    for (int index = 0; index < points.length; index++) {
      cancellation.throwIfCancelled();
      var feature = dataset.features().get(index);
      Geometry point = feature.geometry().getCentroid();
      if (project) point = crs.transform(point, dataset.crs(), outputCrs);
      Coordinate coordinate = point.getCoordinate();
      points[index] = new double[] {coordinate.x, coordinate.y};
      weights[index] = number(feature.properties().get(weightField), 1);
      if (!(weights[index] >= 0))
        throw new IllegalArgumentException("Weights must be non-negative");
    }
    double bandwidth = parameters.path("bandwidth_meters").asDouble(autoBandwidth(points));
    double cellSize = parameters.path("cell_size_meters").asDouble(bandwidth / 4);
    if (!(bandwidth > 0) || !(cellSize > 0))
      throw new IllegalArgumentException("Bandwidth and cell size must be positive");
    String kernel = parameters.path("kernel").asString("gaussian").toLowerCase(Locale.ROOT);
    if (!KERNELS.contains(kernel))
      throw new IllegalArgumentException("Unsupported kernel: " + kernel);
    int maxCells =
        Math.max(100, Math.min(parameters.path("max_grid_cells").asInt(1_000_000), 1_000_000));
    Grid grid = compute(points, weights, bandwidth, cellSize, kernel, maxCells, cancellation);
    if (parameters.path("normalize").asBoolean(false) && grid.maximum() > 0) {
      for (int index = 0; index < grid.values().length; index++)
        grid.values()[index] /= (float) grid.maximum();
      grid = grid.withRange(0, 1);
    }
    return write(workspace, input, outputCrs, grid, bandwidth, kernel, parameters, cancellation);
  }

  private JsonNode write(
      Path workspace,
      Path input,
      String outputCrs,
      Grid grid,
      double bandwidth,
      String kernel,
      JsonNode parameters,
      CancellationSignal cancellation) {
    Path directory =
        WorkspaceGisPaths.output(
                workspace,
                parameters.path("output_dir").asString("kde_output/.keep"),
                "kde_output/.keep")
            .getParent();
    Path rasterPath = directory.resolve(stem(input) + "_density.tif");
    List<String> outputs = new ArrayList<>();
    try {
      writeGeoTiff(rasterPath, outputCrs, grid);
      outputs.add(relative(workspace, rasterPath));
      String contoursPath = "";
      String polygonsPath = "";
      int levels = Math.max(2, Math.min(parameters.path("n_contours").asInt(10), 50));
      if (parameters.path("output_contours").asBoolean(false)) {
        Path path = directory.resolve(stem(input) + "_contours.geojson");
        writeJson(path, contours(grid, levels, cancellation));
        contoursPath = relative(workspace, path);
        outputs.add(contoursPath);
      }
      if (parameters.path("output_polygons").asBoolean(false)) {
        Path path = directory.resolve(stem(input) + "_density_polygons.geojson");
        writeJson(path, polygons(grid, levels, cancellation));
        polygonsPath = relative(workspace, path);
        outputs.add(polygonsPath);
      }
      ObjectNode result = mapper.createObjectNode();
      result.put("success", true);
      result.put("n_features", grid.featureCount());
      result.put("bandwidth", bandwidth);
      result.put("cell_size", grid.cellSize());
      result.put("kernel", kernel);
      result.putArray("grid_size").add(grid.width()).add(grid.height());
      result.putArray("density_range").add(grid.minimum()).add(grid.maximum());
      result.put("raster_path", relative(workspace, rasterPath));
      if (!contoursPath.isBlank()) result.put("contours_path", contoursPath);
      if (!polygonsPath.isBlank()) result.put("polygons_path", polygonsPath);
      result.putPOJO("output_files", outputs);
      result
          .putObject("statistics")
          .put("mean", mean(grid.values()))
          .put("sum", sum(grid.values()));
      return result;
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot write KDE output", exception);
    }
  }

  private static Grid compute(
      double[][] points,
      double[] weights,
      double bandwidth,
      double requestedCellSize,
      String kernel,
      int maxCells,
      CancellationSignal cancellation) {
    double minX =
        Arrays.stream(points).mapToDouble(value -> value[0]).min().orElseThrow() - 2 * bandwidth;
    double maxX =
        Arrays.stream(points).mapToDouble(value -> value[0]).max().orElseThrow() + 2 * bandwidth;
    double minY =
        Arrays.stream(points).mapToDouble(value -> value[1]).min().orElseThrow() - 2 * bandwidth;
    double maxY =
        Arrays.stream(points).mapToDouble(value -> value[1]).max().orElseThrow() + 2 * bandwidth;
    double cellSize = requestedCellSize;
    int width = Math.max(1, (int) Math.ceil((maxX - minX) / cellSize));
    int height = Math.max(1, (int) Math.ceil((maxY - minY) / cellSize));
    long cells = (long) width * height;
    if (cells > maxCells) {
      cellSize *= Math.sqrt(cells / (double) maxCells);
      width = Math.max(1, (int) Math.ceil((maxX - minX) / cellSize));
      height = Math.max(1, (int) Math.ceil((maxY - minY) / cellSize));
    }
    float[] values = new float[width * height];
    double totalWeight = Arrays.stream(weights).sum();
    if (!(totalWeight > 0)) totalWeight = points.length;
    double minimum = Double.POSITIVE_INFINITY;
    double maximum = Double.NEGATIVE_INFINITY;
    for (int y = 0; y < height; y++) {
      cancellation.throwIfCancelled();
      double py = maxY - (y + 0.5) * cellSize;
      for (int x = 0; x < width; x++) {
        double px = minX + (x + 0.5) * cellSize;
        double density = 0;
        for (int point = 0; point < points.length; point++) {
          double u = Math.hypot(px - points[point][0], py - points[point][1]) / bandwidth;
          density += weights[point] * kernel(kernel, u) / (bandwidth * bandwidth);
        }
        density /= totalWeight;
        values[y * width + x] = (float) density;
        minimum = Math.min(minimum, density);
        maximum = Math.max(maximum, density);
      }
    }
    return new Grid(
        values, width, height, minX, minY, maxX, maxY, cellSize, minimum, maximum, points.length);
  }

  private void writeGeoTiff(Path path, String outputCrs, Grid grid) throws IOException {
    BandedSampleModel model =
        new BandedSampleModel(DataBuffer.TYPE_FLOAT, grid.width(), grid.height(), 1);
    WritableRaster raster =
        Raster.createWritableRaster(
            model, new DataBufferFloat(grid.values(), grid.values().length), new Point());
    ReferencedEnvelope envelope =
        new ReferencedEnvelope(
            grid.minX(), grid.maxX(), grid.minY(), grid.maxY(), crs.decode(outputCrs));
    var coverage = new GridCoverageFactory().create("kernel_density", raster, envelope);
    GeoTiffWriter writer = new GeoTiffWriter(path.toFile());
    try {
      writer.write(coverage);
    } finally {
      coverage.dispose(true);
      writer.dispose();
    }
  }

  private ObjectNode contours(Grid grid, int levels, CancellationSignal cancellation) {
    ObjectNode collection = collection();
    ArrayNode features = (ArrayNode) collection.path("features");
    for (int level = 1; level < levels && features.size() < 50_000; level++) {
      double threshold = grid.minimum() + (grid.maximum() - grid.minimum()) * level / levels;
      for (int y = 0; y < grid.height() - 1 && features.size() < 50_000; y++) {
        cancellation.throwIfCancelled();
        for (int x = 0; x < grid.width() - 1 && features.size() < 50_000; x++) {
          float left = grid.values()[y * grid.width() + x];
          float right = grid.values()[y * grid.width() + x + 1];
          if ((left < threshold) == (right < threshold)) continue;
          double px = grid.minX() + (x + 1) * grid.cellSize();
          double top = grid.maxY() - y * grid.cellSize();
          ObjectNode feature = features.addObject();
          feature.put("type", "Feature");
          ArrayNode line =
              feature.putObject("geometry").put("type", "LineString").putArray("coordinates");
          line.addArray().add(px).add(top);
          line.addArray().add(px).add(top - grid.cellSize());
          feature.putObject("properties").put("density_value", threshold).put("class_id", level);
        }
      }
    }
    return collection;
  }

  private ObjectNode polygons(Grid grid, int levels, CancellationSignal cancellation) {
    ObjectNode collection = collection();
    ArrayNode features = (ArrayNode) collection.path("features");
    for (int y = 0; y < grid.height() && features.size() < 50_000; y++) {
      cancellation.throwIfCancelled();
      for (int x = 0; x < grid.width() && features.size() < 50_000; x++) {
        double value = grid.values()[y * grid.width() + x];
        if (value <= grid.minimum()) continue;
        int classId =
            Math.min(
                levels - 1,
                (int)
                    ((value - grid.minimum())
                        / Math.max(1e-30, grid.maximum() - grid.minimum())
                        * levels));
        double minX = grid.minX() + x * grid.cellSize();
        double maxX = minX + grid.cellSize();
        double maxY = grid.maxY() - y * grid.cellSize();
        double minY = maxY - grid.cellSize();
        ObjectNode feature = features.addObject();
        feature.put("type", "Feature");
        ArrayNode ring =
            feature.putObject("geometry").put("type", "Polygon").putArray("coordinates").addArray();
        ring.addArray().add(minX).add(minY);
        ring.addArray().add(maxX).add(minY);
        ring.addArray().add(maxX).add(maxY);
        ring.addArray().add(minX).add(maxY);
        ring.addArray().add(minX).add(minY);
        feature.putObject("properties").put("density_value", value).put("class_id", classId);
      }
    }
    return collection;
  }

  private ObjectNode collection() {
    ObjectNode value = mapper.createObjectNode();
    value.put("type", "FeatureCollection");
    value.putArray("features");
    return value;
  }

  private void writeJson(Path path, JsonNode value) throws IOException {
    Files.writeString(
        path,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
        StandardCharsets.UTF_8);
  }

  private static double kernel(String name, double u) {
    return switch (name) {
      case "gaussian" -> Math.exp(-0.5 * u * u) / (2 * Math.PI);
      case "epanechnikov" -> Math.abs(u) <= 1 ? 0.75 * (1 - u * u) : 0;
      case "uniform" -> Math.abs(u) <= 1 ? 0.5 : 0;
      case "triangular" -> Math.abs(u) <= 1 ? 1 - Math.abs(u) : 0;
      case "quartic" -> Math.abs(u) <= 1 ? (15.0 / 16) * Math.pow(1 - u * u, 2) : 0;
      case "cosine" -> Math.abs(u) <= 1 ? (Math.PI / 4) * Math.cos(Math.PI * u / 2) : 0;
      default -> throw new IllegalArgumentException("Unknown kernel");
    };
  }

  private static double autoBandwidth(double[][] points) {
    double meanX = Arrays.stream(points).mapToDouble(value -> value[0]).average().orElse(0);
    double meanY = Arrays.stream(points).mapToDouble(value -> value[1]).average().orElse(0);
    double variance =
        Arrays.stream(points)
                .mapToDouble(value -> Math.pow(value[0] - meanX, 2) + Math.pow(value[1] - meanY, 2))
                .average()
                .orElse(0)
            / 2;
    return Math.max(100, Math.pow(points.length, -1.0 / 6) * Math.sqrt(variance) * 1.5);
  }

  private static double number(Object value, double fallback) {
    if (value instanceof Number number) return number.doubleValue();
    try {
      return value == null ? fallback : Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static double mean(float[] values) {
    return values.length == 0 ? 0 : sum(values) / values.length;
  }

  private static double sum(float[] values) {
    double result = 0;
    for (float value : values) result += value;
    return result;
  }

  private static String stem(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }

  private static String relative(Path workspace, Path value) {
    return workspace.toAbsolutePath().normalize().relativize(value).toString().replace('\\', '/');
  }

  private record Grid(
      float[] values,
      int width,
      int height,
      double minX,
      double minY,
      double maxX,
      double maxY,
      double cellSize,
      double minimum,
      double maximum,
      int featureCount) {
    Grid withRange(double newMinimum, double newMaximum) {
      return new Grid(
          values,
          width,
          height,
          minX,
          minY,
          maxX,
          maxY,
          cellSize,
          newMinimum,
          newMaximum,
          featureCount);
    }
  }
}
