package org.opengis.ai.model;

/** Semantic stream events emitted by every provider adapter. */
public enum LlmChunkKind {
  TEXT,
  TOOL_CALL,
  USAGE,
  DONE
}
