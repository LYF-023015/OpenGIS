package org.opengis.ai.model;

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
