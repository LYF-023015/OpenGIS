package org.opengis.gis.vector;

import org.opengis.gis.model.GisMetadata;
import tools.jackson.databind.JsonNode;

/** Direct GeoJSON transfer plus source metadata. */
public record VectorLoadResult(JsonNode geojson, GisMetadata metadata, boolean truncated) {}
