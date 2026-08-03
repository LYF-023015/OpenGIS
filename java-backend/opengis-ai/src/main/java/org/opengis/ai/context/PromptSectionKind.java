package org.opengis.ai.context;

/** Canonical request order: stable instructions first, append-only history, dynamic tail last. */
public enum PromptSectionKind {
  SYSTEM,
  CAPABILITY_MANIFEST,
  TOOL_PROTOCOL,
  USER_PREFERENCES,
  CONVERSATION_SUMMARY,
  MEMORY,
  WORKING_STATE,
  HISTORY,
  TOOL_OBSERVATION,
  RUNTIME
}
