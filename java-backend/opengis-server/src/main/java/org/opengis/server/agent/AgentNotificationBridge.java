package org.opengis.server.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.agent.telemetry.AgentEvent;
import org.opengis.agent.telemetry.AgentEventSink;
import org.opengis.server.transport.UiRpcGateway;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Projects internal facts to Python-compatible MessagePart notifications and archives. */
final class AgentNotificationBridge implements AgentEventSink {
  private final String connectionId;
  private final String runId;
  private final String conversationId;
  private final UiRpcGateway ui;
  private final RunArchive archive;
  private final ObjectMapper mapper;
  private final StringBuilder streamedText = new StringBuilder();

  AgentNotificationBridge(
      String connectionId,
      String runId,
      String conversationId,
      UiRpcGateway ui,
      RunArchive archive,
      ObjectMapper mapper) {
    this.connectionId = connectionId;
    this.runId = runId;
    this.conversationId = conversationId;
    this.ui = ui;
    this.archive = archive;
    this.mapper = mapper;
  }

  void streamStart() {
    part("turn-" + runId, "turn", "running", "", Map.of("kind", "stream_start"));
  }

  void finish(String status, String answer, String error) {
    if ("completed".equals(status)) {
      part("answer-" + runId, "text", "completed", answer, Map.of("role", "assistant"));
      part("turn-" + runId, "turn", "completed", "", Map.of("kind", "stream_end"));
    } else if ("cancelled".equals(status)) {
      notify("chat.cancelled", Map.of("run_id", runId, "conversation_id", conversationId));
      part("turn-" + runId, "turn", "cancelled", "", Map.of("kind", "stream_end"));
    } else {
      if (answer != null && !answer.isBlank()) {
        part("answer-" + runId, "text", "completed", answer, Map.of("role", "assistant"));
      }
      part("error-" + runId, "error", "failed", error, Map.of("run_id", runId));
      part("turn-" + runId, "turn", "failed", "", Map.of("kind", "stream_end"));
    }
  }

  @Override
  public synchronized void emit(AgentEvent event) {
    archive.appendEvent((ObjectNode) mapper.valueToTree(event));
    if ("agent.provider.chunk".equals(event.type())
        && "text".equals(String.valueOf(event.data().get("kind")))) {
      streamedText.append(String.valueOf(event.data().getOrDefault("text", "")));
      part(
          "answer-" + runId,
          "text",
          "streaming",
          streamedText.toString(),
          Map.of("role", "assistant", "text_mode", "snapshot"));
    } else if ("agent.tool.settled".equals(event.type())) {
      String callId = String.valueOf(event.data().getOrDefault("call_id", ""));
      part(
          "tool-" + runId + "-" + callId,
          "tool_output",
          "completed".equals(event.data().get("status")) ? "completed" : "failed",
          "",
          event.data());
    }
  }

  private void part(String id, String type, String status, String text, Map<String, ?> data) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("id", id);
    part.put("type", type);
    part.put("status", status);
    part.put("text", text == null ? "" : text);
    part.put("run_id", runId);
    part.put("runId", runId);
    part.put("created_at", System.currentTimeMillis());
    part.put("data", data == null ? Map.of() : data);
    ObjectNode archived = (ObjectNode) mapper.valueToTree(part);
    archive.appendMessagePart(archived);
    notify("chat.message_part", Map.of("part", part));
  }

  private void notify(String method, Map<String, Object> params) {
    if (connectionId == null || connectionId.isBlank()) {
      return;
    }
    try {
      ui.notify(connectionId, method, params);
    } catch (RuntimeException ignored) {
      // The connection may disappear while the cancellation callback is running.
    }
  }
}
