package org.opengis.knowledge.memory;

/** Lifetime and visibility boundary for one memory record. */
public enum MemoryScope {
  GLOBAL,
  WORKSPACE,
  CONVERSATION,
  RUN
}
