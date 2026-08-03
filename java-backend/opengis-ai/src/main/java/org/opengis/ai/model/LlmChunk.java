package org.opengis.ai.model;

/** One normalized streaming delta. Tool arguments may arrive in fragments. */
public record LlmChunk(
    LlmChunkKind kind,
    String text,
    int toolIndex,
    String toolCallId,
    String toolName,
    String argumentsDelta,
    String finishReason,
    LlmUsage usage) {
  public LlmChunk {
    text = text == null ? "" : text;
    toolCallId = toolCallId == null ? "" : toolCallId;
    toolName = toolName == null ? "" : toolName;
    argumentsDelta = argumentsDelta == null ? "" : argumentsDelta;
    finishReason = finishReason == null ? "" : finishReason;
    usage = usage == null ? LlmUsage.EMPTY : usage;
  }

  public static LlmChunk text(String delta) {
    return new LlmChunk(LlmChunkKind.TEXT, delta, -1, "", "", "", "", LlmUsage.EMPTY);
  }

  public static LlmChunk tool(int index, String id, String name, String argumentsDelta) {
    return new LlmChunk(
        LlmChunkKind.TOOL_CALL, "", index, id, name, argumentsDelta, "", LlmUsage.EMPTY);
  }

  public static LlmChunk usage(LlmUsage usage) {
    return new LlmChunk(LlmChunkKind.USAGE, "", -1, "", "", "", "", usage);
  }

  public static LlmChunk done(String finishReason) {
    return new LlmChunk(LlmChunkKind.DONE, "", -1, "", "", "", finishReason, LlmUsage.EMPTY);
  }
}
