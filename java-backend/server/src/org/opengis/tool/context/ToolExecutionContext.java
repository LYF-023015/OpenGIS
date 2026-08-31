/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.context;

import java.nio.file.Path;
import java.util.Map;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.permission.PermissionAction;

/** Explicit immutable context; no ThreadLocal or dependency on AgentProfile. */
public record ToolExecutionContext(
    Path workspace,
    String runId,
    String conversationId,
    String profileName,
    Map<String, PermissionAction> profileOverrides,
    PermissionAction defaultPermission,
    CancellationToken cancellation,
    ToolEventSink eventSink,
    UiRpcPort uiRpc) {
  public ToolExecutionContext {
    if (workspace == null) {
      throw new IllegalArgumentException("Workspace is required");
    }
    workspace = workspace.toAbsolutePath().normalize();
    runId = runId == null || runId.isBlank() ? "direct" : runId;
    profileName = profileName == null || profileName.isBlank() ? "gis-build" : profileName;
    profileOverrides = profileOverrides == null ? Map.of() : Map.copyOf(profileOverrides);
    defaultPermission = defaultPermission == null ? PermissionAction.ALLOW : defaultPermission;
    cancellation = cancellation == null ? new CancellationToken() : cancellation;
    eventSink = eventSink == null ? ToolEventSink.noop() : eventSink;
    uiRpc = uiRpc == null ? UiRpcPort.disconnected() : uiRpc;
  }
}
