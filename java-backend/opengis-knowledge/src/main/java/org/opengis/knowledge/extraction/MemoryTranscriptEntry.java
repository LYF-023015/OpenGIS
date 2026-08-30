package org.opengis.knowledge.extraction;

/** Provider-neutral history projection used by deterministic post-run extraction. */
public record MemoryTranscriptEntry(String role, String content, String name) {
  public MemoryTranscriptEntry {
    role = role == null ? "unknown" : role;
    content = content == null ? "" : content;
    name = name == null ? "" : name;
  }
}
