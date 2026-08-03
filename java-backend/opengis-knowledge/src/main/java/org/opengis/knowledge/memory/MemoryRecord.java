package org.opengis.knowledge.memory;

import java.time.Instant;
import java.util.Map;

/** Durable knowledge with provenance; prompt text is selected separately per task. */
public record MemoryRecord(
    String id,
    MemoryKind kind,
    String content,
    String source,
    Instant createdAt,
    Map<String, String> metadata) {
  public MemoryRecord {
    if (id == null || id.isBlank() || kind == null || content == null || content.isBlank()) {
      throw new IllegalArgumentException("Memory id, kind, and content are required");
    }
    source = source == null ? "unknown" : source;
    createdAt = createdAt == null ? Instant.now() : createdAt;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
