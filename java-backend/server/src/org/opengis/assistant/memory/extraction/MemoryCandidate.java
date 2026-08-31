/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory.extraction;

import java.util.Map;
import org.opengis.assistant.memory.MemoryKind;
import org.opengis.assistant.memory.MemoryScope;

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
