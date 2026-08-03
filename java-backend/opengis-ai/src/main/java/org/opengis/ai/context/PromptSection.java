package org.opengis.ai.context;

import java.util.List;
import java.util.Map;
import org.opengis.ai.model.LlmMessage;

/** Logical request section used for budgeting, stable-prefix checks, and compaction. */
public record PromptSection(
    String id,
    PromptSectionKind kind,
    List<LlmMessage> messages,
    PromptStability stability,
    PromptCachePolicy cachePolicy,
    Map<String, Object> metadata) {
  public PromptSection {
    if (id == null || id.isBlank() || kind == null) {
      throw new IllegalArgumentException("Prompt section id and kind are required");
    }
    messages = messages == null ? List.of() : List.copyOf(messages);
    stability = stability == null ? PromptStability.TURN_DYNAMIC : stability;
    cachePolicy = cachePolicy == null ? PromptCachePolicy.NONE : cachePolicy;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
