package org.opengis.tool.api;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One immutable function call entering the tool pipeline. */
public record ToolCall(String id, String name, JsonNode arguments) {
  public ToolCall {
    id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool name is required");
    }
  }
}
