/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model.context;

import java.util.List;
import java.util.Map;
import org.opengis.assistant.model.LlmMessage;

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

  /** Canonical request order: stable instructions first, append-only history, dynamic tail last. */
  public enum PromptSectionKind {
    SYSTEM,
    CAPABILITY_MANIFEST,
    TOOL_PROTOCOL,
    USER_PREFERENCES,
    CONVERSATION_SUMMARY,
    MEMORY,
    WORKING_STATE,
    HISTORY,
    TOOL_OBSERVATION,
    RUNTIME
  }

  public enum PromptStability {
    STATIC,
    WORKSPACE_STATIC,
    SESSION_STATIC,
    TURN_DYNAMIC
  }

  public enum PromptCachePolicy {
    NONE,
    CACHEABLE,
    BREAKPOINT
  }
}
