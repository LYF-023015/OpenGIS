package org.opengis.ai.model;

import tools.jackson.databind.JsonNode;

/** A fully assembled model-requested tool invocation. */
public record LlmToolCall(String id, String name, JsonNode arguments) {
  public LlmToolCall {
    id = id == null ? "" : id;
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool call name is required");
    }
  }
}
