/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.datasource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.opengis.gis.error.GisException;
import org.opengis.gis.io.WorkspaceGisPaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared inline-or-workspace GeoJSON materialization contract. */
public final class GeoJsonTransfer {
  public static final int INLINE_LIMIT = 40 * 1024;
  private final ObjectMapper mapper;

  public GeoJsonTransfer(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ObjectNode parse(byte[] bytes) {
    try {
      JsonNode value = mapper.readTree(bytes);
      if (!value.isObject()
          || !"FeatureCollection".equals(value.path("type").asString())
          || !value.path("features").isArray()) {
        throw new GisException("invalid_geojson", "Response is not a GeoJSON FeatureCollection");
      }
      return (ObjectNode) value;
    } catch (GisException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new GisException("invalid_geojson", "Cannot parse GeoJSON response", exception);
    }
  }

  public JsonNode finish(
      Path workspace,
      String outputPath,
      String defaultRelative,
      boolean returnGeoJson,
      ObjectNode collection) {
    try {
      byte[] encoded = mapper.writeValueAsBytes(collection);
      if ((outputPath == null || outputPath.isBlank()) && encoded.length <= INLINE_LIMIT) {
        return collection;
      }
      Path path = WorkspaceGisPaths.output(workspace, outputPath, defaultRelative);
      Files.writeString(
          path,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(collection) + "\n",
          StandardCharsets.UTF_8);
      Summary summary = summarize(collection);
      ObjectNode result = mapper.createObjectNode();
      result.put("success", true);
      result.put("type", "FeatureCollection");
      result.put("path", path.toString());
      result.put("output_path", path.toString());
      result.put("feature_count", summary.featureCount());
      result.put("bytes", encoded.length);
      result.set("bbox", mapper.valueToTree(summary.bbox()));
      result.set("geometry_types", mapper.valueToTree(summary.geometryTypes()));
      if (returnGeoJson) result.set("geojson", collection);
      return result;
    } catch (IOException exception) {
      throw new GisException("geojson_write_failed", "Cannot save GeoJSON", exception);
    }
  }

  public Summary summarize(ObjectNode collection) {
    Bounds bounds = new Bounds();
    Set<String> types = new LinkedHashSet<>();
    for (JsonNode feature : collection.path("features")) {
      JsonNode geometry = feature.path("geometry");
      if (geometry.path("type").isString()) types.add(geometry.path("type").asString());
      visit(geometry.path("coordinates"), bounds);
    }
    return new Summary(collection.path("features").size(), bounds.value(), List.copyOf(types));
  }

  private static void visit(JsonNode node, Bounds bounds) {
    if (!node.isArray() || node.isEmpty()) return;
    if (node.get(0).isNumber()) {
      if (node.size() >= 2) bounds.include(node.get(0).asDouble(), node.get(1).asDouble());
    } else node.forEach(child -> visit(child, bounds));
  }

  public record Summary(int featureCount, double[] bbox, java.util.List<String> geometryTypes) {}

  private static final class Bounds {
    private double minX = Double.POSITIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;

    void include(double x, double y) {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }

    double[] value() {
      return Double.isInfinite(minX) ? null : new double[] {minX, minY, maxX, maxY};
    }
  }
}
