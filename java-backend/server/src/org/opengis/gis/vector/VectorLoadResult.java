/** 文件职责：gis 后端领域：定义领域数据结构与协议。 */
package org.opengis.gis.vector;

import org.opengis.gis.model.GisMetadata;
import tools.jackson.databind.JsonNode;

/** Direct GeoJSON transfer plus source metadata. */
public record VectorLoadResult(JsonNode geojson, GisMetadata metadata, boolean truncated) {}
