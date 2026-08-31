/** 文件职责：gis 后端领域：封装外部系统或通信协议。 */
package org.opengis.gis.osm;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.opengis.core.concurrent.CancellationSignal;
import org.opengis.gis.datasource.BoundedHttpClient;
import org.opengis.gis.datasource.GeoJsonTransfer;
import org.opengis.gis.error.GisException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OpenStreetMap adapter for Overpass and Nominatim with bounded responses. */
public final class OsmAdapter {
  private static final URI OVERPASS = URI.create("https://overpass-api.de/api/interpreter");
  private static final URI NOMINATIM = URI.create("https://nominatim.openstreetmap.org/search");
  private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_:-]{1,80}");
  private static final Set<String> GEOMETRY_TYPES = Set.of("node", "way", "relation");

  private final ObjectMapper mapper;
  private final BoundedHttpClient http;
  private final GeoJsonTransfer transfer;

  public OsmAdapter(ObjectMapper mapper) {
    this(mapper, new BoundedHttpClient(64 * 1024 * 1024));
  }

  OsmAdapter(ObjectMapper mapper, BoundedHttpClient http) {
    this.mapper = mapper;
    this.http = http;
    this.transfer = new GeoJsonTransfer(mapper);
  }

  public JsonNode call(
      String command, JsonNode params, Path workspace, CancellationSignal cancellation) {
    return switch (command) {
      case "search" -> search(params, cancellation);
      case "overpass_query" ->
          finish(
              query(text(params, "query"), timeout(params), cancellation),
              params,
              workspace,
              command);
      case "download_bbox" ->
          finish(
              query(buildBboxQuery(params), timeout(params), cancellation),
              params,
              workspace,
              command);
      case "download_features" -> downloadFeatures(params, workspace, cancellation);
      default -> throw new GisException("osm_unknown_command", "Unknown OSM command: " + command);
    };
  }

  private JsonNode downloadFeatures(
      JsonNode params, Path workspace, CancellationSignal cancellation) {
    JsonNode results = searchParams(text(params, "place"), 1, timeout(params), cancellation);
    if (results.isEmpty()) throw new GisException("osm_place_not_found", "OSM place was not found");
    ArrayNode bbox = (ArrayNode) results.get(0).path("boundingbox");
    ObjectNode copy = (ObjectNode) params.deepCopy();
    copy.put("south", bbox.get(0).asDouble());
    copy.put("north", bbox.get(1).asDouble());
    copy.put("west", bbox.get(2).asDouble());
    copy.put("east", bbox.get(3).asDouble());
    return finish(
        query(buildBboxQuery(copy), timeout(copy), cancellation),
        copy,
        workspace,
        "download_features");
  }

  private JsonNode search(JsonNode params, CancellationSignal cancellation) {
    int limit = Math.max(1, Math.min(params.path("limit").asInt(5), 20));
    ObjectNode result = mapper.createObjectNode().put("success", true);
    result.set(
        "results", searchParams(text(params, "query"), limit, timeout(params), cancellation));
    return result;
  }

  private JsonNode searchParams(
      String query, int limit, Duration timeout, CancellationSignal cancellation) {
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri =
        URI.create(NOMINATIM + "?q=" + encoded + "&format=json&addressdetails=1&limit=" + limit);
    try {
      JsonNode result = mapper.readTree(http.get(uri, timeout, cancellation));
      if (!result.isArray())
        throw new GisException("osm_invalid_response", "Invalid Nominatim response");
      return result;
    } catch (GisException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new GisException("osm_invalid_response", "Cannot parse Nominatim response", exception);
    }
  }

  private ObjectNode query(String raw, Duration timeout, CancellationSignal cancellation) {
    if (raw == null || raw.isBlank() || raw.length() > 20_000) {
      throw new GisException(
          "osm_invalid_query", "Overpass query is required and limited to 20,000 characters");
    }
    String query = raw.contains("[out:") ? raw : "[out:json];" + raw;
    try {
      byte[] response =
          http.postForm(
              OVERPASS,
              "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
              timeout,
              cancellation);
      return osmToGeoJson(mapper.readTree(response), cancellation);
    } catch (GisException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new GisException("osm_invalid_response", "Cannot parse Overpass response", exception);
    }
  }

  ObjectNode osmToGeoJson(JsonNode response, CancellationSignal cancellation) {
    ObjectNode collection = mapper.createObjectNode().put("type", "FeatureCollection");
    ArrayNode features = collection.putArray("features");
    for (JsonNode element : response.path("elements")) {
      cancellation.throwIfCancelled();
      String type = element.path("type").asString();
      JsonNode tags = element.path("tags");
      ObjectNode geometry = null;
      if ("node".equals(type) && tags.isObject() && element.has("lat") && element.has("lon")) {
        geometry = point(element.path("lon").asDouble(), element.path("lat").asDouble());
      } else if ("way".equals(type) && element.path("geometry").isArray()) {
        geometry = way(element.path("geometry"));
      } else if ("relation".equals(type) && element.path("members").isArray()) {
        geometry = relation(element.path("members"));
      }
      if (geometry == null) continue;
      ObjectNode feature = features.addObject().put("type", "Feature");
      feature.set("geometry", geometry);
      ObjectNode properties = feature.putObject("properties");
      if (tags.isObject())
        tags.properties().forEach(entry -> properties.set(entry.getKey(), entry.getValue()));
      properties.put("_osm_id", element.path("id").asLong());
      properties.put("_osm_type", type);
    }
    return collection;
  }

  private ObjectNode relation(JsonNode members) {
    List<List<double[]>> outerSegments = new java.util.ArrayList<>();
    List<List<double[]>> innerSegments = new java.util.ArrayList<>();
    for (JsonNode member : members) {
      if (!"way".equals(member.path("type").asString()) || !member.path("geometry").isArray()) {
        continue;
      }
      List<double[]> segment = coordinates(member.path("geometry"));
      if (segment.size() < 2) continue;
      if ("inner".equals(member.path("role").asString())) innerSegments.add(segment);
      else if ("outer".equals(member.path("role").asString())) outerSegments.add(segment);
    }
    List<List<double[]>> outers = stitchClosedRings(outerSegments);
    if (outers.isEmpty()) return null;
    List<List<double[]>> inners = stitchClosedRings(innerSegments);
    List<List<List<double[]>>> polygons = new java.util.ArrayList<>();
    outers.forEach(outer -> polygons.add(new java.util.ArrayList<>(List.of(outer))));
    for (List<double[]> inner : inners) {
      double[] probe = inner.getFirst();
      for (List<List<double[]>> polygon : polygons) {
        if (contains(polygon.getFirst(), probe)) {
          polygon.add(inner);
          break;
        }
      }
    }
    if (polygons.size() == 1) {
      ObjectNode geometry = mapper.createObjectNode().put("type", "Polygon");
      ArrayNode target = geometry.putArray("coordinates");
      polygons.getFirst().forEach(ring -> target.add(ring(ring)));
      return geometry;
    }
    ObjectNode geometry = mapper.createObjectNode().put("type", "MultiPolygon");
    ArrayNode target = geometry.putArray("coordinates");
    for (List<List<double[]>> polygon : polygons) {
      ArrayNode polygonNode = target.addArray();
      polygon.forEach(value -> polygonNode.add(ring(value)));
    }
    return geometry;
  }

  private List<double[]> coordinates(JsonNode geometry) {
    List<double[]> result = new java.util.ArrayList<>();
    geometry.forEach(
        point -> {
          if (point.has("lon") && point.has("lat")) {
            result.add(new double[] {point.path("lon").asDouble(), point.path("lat").asDouble()});
          }
        });
    return result;
  }

  private static List<List<double[]>> stitchClosedRings(List<List<double[]>> source) {
    List<List<double[]>> pending =
        source.stream()
            .map(value -> new java.util.ArrayList<>(value))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    List<List<double[]>> rings = new java.util.ArrayList<>();
    while (!pending.isEmpty()) {
      List<double[]> current = pending.removeFirst();
      while (!same(current.getFirst(), current.getLast())) {
        int match = -1;
        boolean reverse = false;
        for (int index = 0; index < pending.size(); index++) {
          List<double[]> candidate = pending.get(index);
          if (same(current.getLast(), candidate.getFirst())) {
            match = index;
            break;
          }
          if (same(current.getLast(), candidate.getLast())) {
            match = index;
            reverse = true;
            break;
          }
        }
        if (match < 0) break;
        List<double[]> next = pending.remove(match);
        if (reverse) java.util.Collections.reverse(next);
        current.addAll(next.subList(1, next.size()));
      }
      if (current.size() >= 4 && same(current.getFirst(), current.getLast())) rings.add(current);
    }
    return rings;
  }

  private static boolean same(double[] left, double[] right) {
    return Math.abs(left[0] - right[0]) < 1e-9 && Math.abs(left[1] - right[1]) < 1e-9;
  }

  private static boolean contains(List<double[]> ring, double[] point) {
    boolean inside = false;
    for (int left = 0, right = ring.size() - 1; left < ring.size(); right = left++) {
      double[] a = ring.get(left);
      double[] b = ring.get(right);
      if ((a[1] > point[1]) != (b[1] > point[1])
          && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1] + 1e-15) + a[0]) {
        inside = !inside;
      }
    }
    return inside;
  }

  private ArrayNode ring(List<double[]> values) {
    ArrayNode result = mapper.createArrayNode();
    values.forEach(value -> result.addArray().add(value[0]).add(value[1]));
    return result;
  }

  private ObjectNode point(double lon, double lat) {
    ObjectNode geometry = mapper.createObjectNode().put("type", "Point");
    geometry.putArray("coordinates").add(lon).add(lat);
    return geometry;
  }

  private ObjectNode way(JsonNode nodes) {
    ArrayNode coordinates = mapper.createArrayNode();
    nodes.forEach(
        node ->
            coordinates
                .addArray()
                .add(node.path("lon").asDouble())
                .add(node.path("lat").asDouble()));
    if (coordinates.size() < 2) return null;
    boolean closed =
        coordinates.get(0).equals(coordinates.get(coordinates.size() - 1))
            && coordinates.size() >= 4;
    ObjectNode geometry = mapper.createObjectNode().put("type", closed ? "Polygon" : "LineString");
    if (closed) geometry.putArray("coordinates").add(coordinates);
    else geometry.set("coordinates", coordinates);
    return geometry;
  }

  private JsonNode finish(ObjectNode geojson, JsonNode params, Path workspace, String command) {
    return transfer.finish(
        workspace,
        optionalText(params, "output_path", optionalText(params, "save_path", "")),
        "osm/" + command + ".geojson",
        params.path("return_geojson").asBoolean(false),
        geojson);
  }

  private static String buildBboxQuery(JsonNode params) {
    double south = coordinate(params, "south", -90, 90);
    double west = coordinate(params, "west", -180, 180);
    double north = coordinate(params, "north", -90, 90);
    double east = coordinate(params, "east", -180, 180);
    if (south >= north || west >= east)
      throw new GisException("osm_invalid_bbox", "Invalid OSM bbox ordering");
    String key = text(params, "key");
    if (!TAG.matcher(key).matches())
      throw new GisException("osm_invalid_tag", "Invalid OSM tag key");
    String type = optionalText(params, "geometry_type", "way").toLowerCase(Locale.ROOT);
    if (!GEOMETRY_TYPES.contains(type))
      throw new GisException("osm_invalid_geometry_type", "Invalid OSM geometry type");
    String value = optionalText(params, "value", "*");
    String selector =
        "*".equals(value) ? "[\"" + key + "\"]" : "[\"" + key + "\"=\"" + escape(value) + "\"]";
    return type + selector + "(" + south + "," + west + "," + north + "," + east + ");out geom;";
  }

  private static Duration timeout(JsonNode params) {
    return Duration.ofSeconds(Math.max(5, Math.min(params.path("timeout").asInt(45), 120)));
  }

  private static double coordinate(JsonNode params, String field, double min, double max) {
    if (!params.path(field).isNumber())
      throw new GisException("osm_invalid_bbox", "Missing bbox field: " + field);
    double value = params.path(field).asDouble();
    if (value < min || value > max)
      throw new GisException("osm_invalid_bbox", "Invalid bbox field: " + field);
    return value;
  }

  private static String text(JsonNode params, String field) {
    String value = optionalText(params, field, "");
    if (value.isBlank())
      throw new GisException("osm_invalid_params", "Missing OSM parameter: " + field);
    return value;
  }

  private static String optionalText(JsonNode params, String field, String fallback) {
    return params.path(field).isString() ? params.path(field).asString() : fallback;
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
