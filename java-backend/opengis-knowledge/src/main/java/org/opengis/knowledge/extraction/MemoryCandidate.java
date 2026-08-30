package org.opengis.knowledge.extraction;

import java.util.Map;
import org.opengis.knowledge.memory.MemoryKind;
import org.opengis.knowledge.memory.MemoryScope;

/** Candidate knowledge is validated before it becomes durable memory. */
public record MemoryCandidate(
    MemoryKind kind,
    String content,
    MemoryScope scope,
    String scopeId,
    double confidence,
    double importance,
    String reason,
    Map<String, String> metadata) {}
