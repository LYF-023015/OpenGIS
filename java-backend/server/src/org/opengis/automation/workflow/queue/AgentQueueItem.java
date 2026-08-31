/** 文件职责：workflow 后端领域：可复用界面组件。 */
package org.opengis.automation.workflow.queue;

import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Persisted queue command. Queue id and inbox id are intentionally identical in Java. */
public record AgentQueueItem(
    String id,
    String prompt,
    Path workspace,
    String conversationId,
    String profileName,
    String connectionId,
    String workflowId,
    QueueStatus status,
    String runId,
    String error,
    int attempts,
    long createdAt,
    long updatedAt,
    Map<String, JsonNode> metadata) {
  public AgentQueueItem {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public AgentQueueItem withState(
      QueueStatus next, String nextRunId, String nextError, int nextAttempts) {
    return new AgentQueueItem(
        id,
        prompt,
        workspace,
        conversationId,
        profileName,
        connectionId,
        workflowId,
        next,
        nextRunId,
        nextError == null ? "" : nextError,
        nextAttempts,
        createdAt,
        System.currentTimeMillis(),
        metadata);
  }
}
