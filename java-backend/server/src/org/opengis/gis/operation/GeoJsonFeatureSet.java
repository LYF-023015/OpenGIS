/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.operation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.opengis.core.concurrent.CancellationSignal;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.vector.VectorLoader;
import org.opengis.core.persistence.JsonTypeReferences;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Internal non-GeoTools transfer model used by built-in algorithms. */
public final class GeoJsonFeatureSet {
  private final ObjectMapper mapper;
  private final List<Feature> features;
  private final String crs;

  private GeoJsonFeatureSet(ObjectMapper mapper, List<Feature> features, String crs) {
    this.mapper = mapper;
    this.features = List.copyOf(features);
    this.crs = crs == null || crs.isBlank() ? "EPSG:4326" : crs;
  }

  public static GeoJsonFeatureSet load(
      ObjectMapper mapper, Path path, CancellationSignal cancellation) {
    var result = new VectorLoader(mapper, new CrsService()).load(path, 100_000, cancellation);
    List<Feature> features = new ArrayList<>();
    GeoJsonReader reader = new GeoJsonReader();
    for (JsonNode value : result.geojson().path("features")) {
      cancellation.throwIfCancelled();
      JsonNode geometryNode = value.path("geometry");
      if (geometryNode.isMissingNode() || geometryNode.isNull()) continue;
      try {
        Geometry geometry = reader.read(mapper.writeValueAsString(geometryNode));
        Map<String, Object> properties =
            value.path("properties").isObject()
                ? mapper.convertValue(
                    value.path("properties"), JsonTypeReferences.STRING_OBJECT_LINKED_MAP)
                : new LinkedHashMap<>();
        features.add(
            new Feature(
                value.path("id").asString(""), geometry, properties, geometryNode.deepCopy()));
      } catch (org.locationtech.jts.io.ParseException exception) {
        throw new IllegalArgumentException("Invalid GeoJSON geometry", exception);
      }
    }
    return new GeoJsonFeatureSet(mapper, features, result.metadata().crs());
  }

  public List<Feature> features() {
    return features;
  }

  public String crs() {
    return crs;
  }

  public ObjectNode toGeoJson(String labelField, int[] labels) {
    ObjectNode collection = mapper.createObjectNode();
    collection.put("type", "FeatureCollection");
    ArrayNode output = collection.putArray("features");
    for (int index = 0; index < features.size(); index++) {
      Feature feature = features.get(index);
      ObjectNode value = output.addObject();
      value.put("type", "Feature");
      if (!feature.id().isBlank()) value.put("id", feature.id());
      value.set("geometry", feature.geometryJson());
      ObjectNode properties = mapper.valueToTree(feature.properties());
      if (labels != null) properties.put(labelField, labels[index]);
      value.set("properties", properties);
    }
    return collection;
  }

  public record Feature(
      String id, Geometry geometry, Map<String, Object> properties, JsonNode geometryJson) {}
}
