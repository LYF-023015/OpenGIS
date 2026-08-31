/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Stable provider-neutral description of one callable OpenGIS tool. */
public record ToolDefinition(
    String name,
    String displayName,
    String description,
    String category,
    String group,
    String version,
    ToolRisk risk,
    JsonNode inputSchema,
    List<String> tags) {
  public ToolDefinition {
    tags = tags == null ? List.of() : List.copyOf(tags);
    if (name == null || name.isBlank() || inputSchema == null || !inputSchema.isObject()) {
      throw new IllegalArgumentException("Tool name and object input schema are required");
    }
  }
}
