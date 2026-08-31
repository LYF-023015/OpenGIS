/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model;

import java.util.List;

/** Provider-neutral message persisted by context and consumed by the agent loop. */
public record LlmMessage(
    LlmRole role, String content, String name, String toolCallId, List<LlmToolCall> toolCalls) {
  public LlmMessage {
    if (role == null) {
      throw new IllegalArgumentException("Message role is required");
    }
    content = content == null ? "" : content;
    name = name == null ? "" : name;
    toolCallId = toolCallId == null ? "" : toolCallId;
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static LlmMessage system(String content) {
    return new LlmMessage(LlmRole.SYSTEM, content, "", "", List.of());
  }

  public static LlmMessage user(String content) {
    return new LlmMessage(LlmRole.USER, content, "", "", List.of());
  }

  public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
    return new LlmMessage(LlmRole.ASSISTANT, content, "", "", toolCalls);
  }

  public static LlmMessage tool(String callId, String name, String content) {
    return new LlmMessage(LlmRole.TOOL, content, name, callId, List.of());
  }
}
