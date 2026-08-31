/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory.extraction;

/** Provider-neutral history projection used by deterministic post-run extraction. */
public record MemoryTranscriptEntry(String role, String content, String name) {
  public MemoryTranscriptEntry {
    role = role == null ? "unknown" : role;
    content = content == null ? "" : content;
    name = name == null ? "" : name;
  }
}
