package org.opengis.ai.model;

import java.util.List;

/** Fully assembled response returned after the provider stream reaches a terminal event. */
public record LlmResponse(
    String content, List<LlmToolCall> toolCalls, String finishReason, LlmUsage usage) {
  public LlmResponse {
    content = content == null ? "" : content;
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    finishReason = finishReason == null || finishReason.isBlank() ? "stop" : finishReason;
    usage = usage == null ? LlmUsage.EMPTY : usage;
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}
