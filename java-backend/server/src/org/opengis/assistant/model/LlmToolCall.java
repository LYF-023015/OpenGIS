/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model;

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
