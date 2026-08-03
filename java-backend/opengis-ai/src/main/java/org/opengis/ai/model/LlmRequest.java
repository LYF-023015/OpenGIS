package org.opengis.ai.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Complete request after context projection and budgeting. */
public record LlmRequest(
    String model,
    List<LlmMessage> messages,
    List<LlmToolDefinition> tools,
    double temperature,
    int maxTokens,
    Duration timeout,
    Map<String, Object> metadata) {
  public LlmRequest {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("Model is required");
    }
    messages = messages == null ? List.of() : List.copyOf(messages);
    tools = tools == null ? List.of() : List.copyOf(tools);
    temperature = Math.max(0.0, Math.min(2.0, temperature));
    maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
    timeout =
        timeout == null || timeout.isNegative() || timeout.isZero()
            ? Duration.ofMinutes(5)
            : timeout;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
