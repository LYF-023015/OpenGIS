package org.opengis.ai.context;

import java.util.List;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmToolDefinition;
import tools.jackson.databind.ObjectMapper;

/** Deterministic conservative estimate used when a provider tokenizer is unavailable. */
public final class TokenEstimator {
  private static final int CHARS_PER_TOKEN = 4;
  private final ObjectMapper mapper;

  public TokenEstimator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public int messages(List<LlmMessage> messages) {
    long chars = 0;
    for (LlmMessage message : messages) {
      chars += 12L + message.content().length() + message.name().length();
      chars += mapper.writeValueAsString(message.toolCalls()).length();
    }
    return safeTokens(chars);
  }

  public int tools(List<LlmToolDefinition> tools) {
    return safeTokens(mapper.writeValueAsString(tools).length());
  }

  private static int safeTokens(long chars) {
    return (int)
        Math.min(Integer.MAX_VALUE, Math.max(1, (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN));
  }
}
