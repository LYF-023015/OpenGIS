/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory;

import java.util.Map;

/** Partial update; null values retain the current record value. */
public record MemoryUpdate(
    String content,
    Double confidence,
    Double importance,
    MemoryStatus status,
    Map<String, String> metadata) {
  public MemoryUpdate {
    content = content == null ? null : content.strip();
    if (content != null && content.isBlank()) {
      throw new IllegalArgumentException("Updated memory content must not be blank");
    }
    metadata = metadata == null ? null : Map.copyOf(metadata);
  }
}
