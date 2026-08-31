/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.context;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.opengis.assistant.agent.profile.AgentProfile;
import org.opengis.assistant.agent.telemetry.AgentEvent;
import org.opengis.assistant.agent.telemetry.AgentEventSink;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.permission.PermissionAction;

/** Explicit identity and runtime ports carried through provider and tool execution. */
public final class AgentRunContext {
  private final Path workspace;
  private final String runId;
  private final String conversationId;
  private final String connectionId;
  private final AgentProfile profile;
  private final CancellationToken cancellation;
  private final AgentEventSink events;
  private final ToolEventSink toolEvents;
  private final UiRpcPort uiRpc;
  private final Map<String, PermissionAction> permissionOverrides;
  private final AtomicLong sequence = new AtomicLong();

  public AgentRunContext(
      Path workspace,
      String runId,
      String conversationId,
      String connectionId,
      AgentProfile profile,
      CancellationToken cancellation,
      AgentEventSink events,
      ToolEventSink toolEvents,
      UiRpcPort uiRpc,
      Map<String, PermissionAction> permissionOverrides) {
    this.workspace = workspace.toAbsolutePath().normalize();
    this.runId = runId;
    this.conversationId = conversationId;
    this.connectionId = connectionId == null ? "" : connectionId;
    this.profile = profile;
    this.cancellation = cancellation;
    this.events = events == null ? AgentEventSink.noop() : events;
    this.toolEvents = toolEvents == null ? ToolEventSink.noop() : toolEvents;
    this.uiRpc = uiRpc == null ? UiRpcPort.disconnected() : uiRpc;
    this.permissionOverrides =
        permissionOverrides == null ? Map.of() : Map.copyOf(permissionOverrides);
  }

  public void emit(String type, Map<String, Object> data) {
    events.emit(
        new AgentEvent(
            sequence.incrementAndGet(),
            Instant.now(),
            type,
            runId,
            conversationId,
            data == null ? Map.of() : Map.copyOf(data)));
  }

  public Path workspace() {
    return workspace;
  }

  public String runId() {
    return runId;
  }

  public String conversationId() {
    return conversationId;
  }

  public String connectionId() {
    return connectionId;
  }

  public AgentProfile profile() {
    return profile;
  }

  public CancellationToken cancellation() {
    return cancellation;
  }

  public ToolEventSink toolEvents() {
    return toolEvents;
  }

  public UiRpcPort uiRpc() {
    return uiRpc;
  }

  public Map<String, PermissionAction> permissionOverrides() {
    return permissionOverrides;
  }
}
