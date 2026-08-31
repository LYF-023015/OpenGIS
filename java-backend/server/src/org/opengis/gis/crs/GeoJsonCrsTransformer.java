/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.crs;

import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.opengis.core.concurrent.CancellationSignal;
import org.opengis.gis.error.GisException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Transforms every GeoJSON feature geometry while preserving properties and IDs. */
public final class GeoJsonCrsTransformer {
  private final ObjectMapper mapper;
  private final CrsService crs;

  public GeoJsonCrsTransformer(ObjectMapper mapper, CrsService crs) {
    this.mapper = mapper;
    this.crs = crs;
  }

  public ObjectNode transform(
      JsonNode input, String source, String target, CancellationSignal cancellation) {
    if (!input.isObject()
        || !"FeatureCollection".equals(input.path("type").asString())
        || !input.path("features").isArray()) {
      throw new GisException("invalid_geojson", "Expected a GeoJSON FeatureCollection");
    }
    ObjectNode output = (ObjectNode) input.deepCopy();
    GeoJsonReader reader = new GeoJsonReader();
    GeoJsonWriter writer = new GeoJsonWriter();
    try {
      for (JsonNode feature : output.path("features")) {
        cancellation.throwIfCancelled();
        JsonNode geometry = feature.path("geometry");
        if (geometry.isNull() || geometry.isMissingNode()) continue;
        var transformed =
            crs.transform(reader.read(mapper.writeValueAsString(geometry)), source, target);
        ((ObjectNode) feature).set("geometry", mapper.readTree(writer.write(transformed)));
      }
      ObjectNode coordinateReference = output.putObject("crs");
      coordinateReference.put("type", "name");
      coordinateReference.putObject("properties").put("name", target);
      return output;
    } catch (GisException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new GisException("geojson_transform_failed", "Cannot transform GeoJSON", exception);
    }
  }
}
