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
    Instant updatedAt,
    Instant lastUsedAt,
    MemoryScope scope,
    String scopeId,
    double confidence,
    double importance,
    int accessCount,
    MemoryStatus status,
    Map<String, String> metadata) {
  public MemoryRecord {
    if (id == null || id.isBlank() || kind == null || content == null || content.isBlank()) {
      throw new IllegalArgumentException("Memory id, kind, and content are required");
    }
    source = source == null ? "unknown" : source;
    createdAt = createdAt == null ? Instant.now() : createdAt;
    updatedAt = updatedAt == null ? createdAt : updatedAt;
    lastUsedAt = lastUsedAt == null ? Instant.EPOCH : lastUsedAt;
    scope = scope == null ? MemoryScope.WORKSPACE : scope;
    scopeId = scopeId == null ? "" : scopeId;
    confidence = bounded(confidence, 0.7);
    importance = bounded(importance, 0.5);
    accessCount = Math.max(0, accessCount);
    status = status == null ? MemoryStatus.ACTIVE : status;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /** Backward-compatible constructor for the original workspace-only record shape. */
  public MemoryRecord(
      String id,
      MemoryKind kind,
      String content,
      String source,
      Instant createdAt,
      Map<String, String> metadata) {
    this(
        id,
        kind,
        content,
        source,
        createdAt,
        createdAt,
        Instant.EPOCH,
        MemoryScope.WORKSPACE,
        "",
        0.7,
        0.5,
        0,
        MemoryStatus.ACTIVE,
        metadata);
  }

  public MemoryRecord update(MemoryUpdate update, Instant now) {
    Map<String, String> merged = new java.util.LinkedHashMap<>(metadata);
    if (update.metadata() != null) {
      merged.putAll(update.metadata());
    }
    return new MemoryRecord(
        id,
        kind,
        update.content() == null ? content : update.content(),
        source,
        createdAt,
        now,
        lastUsedAt,
        scope,
        scopeId,
        update.confidence() == null ? confidence : update.confidence(),
        update.importance() == null ? importance : update.importance(),
        accessCount,
        update.status() == null ? status : update.status(),
        merged);
  }

  public MemoryRecord accessed(Instant now) {
    return new MemoryRecord(
        id,
        kind,
        content,
        source,
        createdAt,
        updatedAt,
        now,
        scope,
        scopeId,
        confidence,
        importance,
        accessCount + 1,
        status,
        metadata);
  }

  private static double bounded(double value, double fallback) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return fallback;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }
}
