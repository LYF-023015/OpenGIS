/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model.context;

import java.util.List;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmToolDefinition;
import tools.jackson.databind.ObjectMapper;

/** Deterministic conservative estimate used when a provider tokenizer is unavailable. */
public final class TokenEstimator {
  private static final int CHARS_PER_TOKEN = 4;
  private final ObjectMapper mapper;

  public TokenEstimator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public int messages(List<LlmMessage> messages) {
    long weightedCharacters = 0;
    for (LlmMessage message : messages) {
      weightedCharacters += 12L;
      weightedCharacters += weightedCharacters(message.content());
      weightedCharacters += weightedCharacters(message.name());
      weightedCharacters += weightedCharacters(mapper.writeValueAsString(message.toolCalls()));
    }
    return safeTokens(weightedCharacters);
  }

  public int tools(List<LlmToolDefinition> tools) {
    return safeTokens(weightedCharacters(mapper.writeValueAsString(tools)));
  }

  private static long weightedCharacters(String value) {
    long weighted = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      weighted += codePoint <= 0x7f ? 1 : CHARS_PER_TOKEN;
      offset += Character.charCount(codePoint);
    }
    return weighted;
  }

  private static int safeTokens(long chars) {
    return (int)
        Math.min(Integer.MAX_VALUE, Math.max(1, (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN));
  }
}
