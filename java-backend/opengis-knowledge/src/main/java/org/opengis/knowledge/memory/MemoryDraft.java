package org.opengis.knowledge.memory;

import java.util.Map;

/** Validated input for creating or upserting durable memory. */
public record MemoryDraft(
    MemoryKind kind,
    String content,
    String source,
    MemoryScope scope,
    String scopeId,
    double confidence,
    double importance,
    Map<String, String> metadata) {
  public MemoryDraft {
    if (kind == null || content == null || content.isBlank()) {
      throw new IllegalArgumentException("Memory kind and content are required");
    }
    content = content.strip();
    source = source == null || source.isBlank() ? "unknown" : source;
    scope = scope == null ? MemoryScope.WORKSPACE : scope;
    scopeId = scopeId == null ? "" : scopeId.strip();
    confidence = clamp(confidence, 0.7);
    importance = clamp(importance, 0.5);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  private static double clamp(double value, double fallback) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return fallback;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }
}
