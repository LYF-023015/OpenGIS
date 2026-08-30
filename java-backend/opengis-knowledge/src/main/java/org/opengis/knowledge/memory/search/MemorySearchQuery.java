package org.opengis.knowledge.memory.search;

import java.util.Set;
import org.opengis.knowledge.memory.MemoryScope;

/** Search constraints keep retrieval relevant and bound the prompt payload. */
public record MemorySearchQuery(
    String text,
    int limit,
    int maxChars,
    Set<MemoryScope> scopes,
    String conversationId,
    String runId) {
  public MemorySearchQuery {
    text = text == null ? "" : text.strip();
    limit = Math.max(0, limit);
    maxChars = Math.max(0, maxChars);
    scopes =
        scopes == null || scopes.isEmpty()
            ? Set.of(MemoryScope.GLOBAL, MemoryScope.WORKSPACE)
            : Set.copyOf(scopes);
    conversationId = conversationId == null ? "" : conversationId;
    runId = runId == null ? "" : runId;
  }

  public static MemorySearchQuery workspace(String text, int limit, int maxChars) {
    return new MemorySearchQuery(
        text, limit, maxChars, Set.of(MemoryScope.GLOBAL, MemoryScope.WORKSPACE), "", "");
  }
}
