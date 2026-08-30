package org.opengis.knowledge.memory;

/** Lifecycle state used by consolidation without destructively deleting provenance. */
public enum MemoryStatus {
  ACTIVE,
  ARCHIVED,
  SUPERSEDED
}
