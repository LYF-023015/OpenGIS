/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model;

import tools.jackson.databind.JsonNode;

/** Provider-neutral function schema. */
public record LlmToolDefinition(String name, String description, JsonNode inputSchema) {
  public LlmToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool name is required");
    }
    description = description == null ? "" : description;
    if (inputSchema == null || !inputSchema.isObject()) {
      throw new IllegalArgumentException("Tool input schema must be an object");
    }
  }
}
