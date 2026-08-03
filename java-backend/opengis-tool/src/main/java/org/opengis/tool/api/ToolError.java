package org.opengis.tool.api;

import tools.jackson.databind.JsonNode;

/** Structured failure safe to archive and return to an LLM or Renderer. */
public record ToolError(String code, String message, JsonNode details, boolean retryable) {}
