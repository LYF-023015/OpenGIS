package org.opengis.gis.crs;

import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.CancellationToken;
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
      JsonNode input, String source, String target, CancellationToken cancellation) {
    if (!input.isObject()
        || !"FeatureCollection".equals(input.path("type").asText())
        || !input.path("features").isArray()) {
      throw new ToolException("invalid_geojson", "Expected a GeoJSON FeatureCollection");
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
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("geojson_transform_failed", "Cannot transform GeoJSON", exception);
    }
  }
}
