/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.execution;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Persisted workflow run snapshot. Node fingerprints make resume side-effect safe. */
public record WorkflowRunSnapshot(
    String runId,
    String workflowId,
    String status,
    String createdAt,
    String updatedAt,
    String error,
    Map<String, NodeState> nodes) {
  public WorkflowRunSnapshot {
    nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
  }

  public record NodeState(
      String nodeId,
      String status,
      int attempts,
      String inputFingerprint,
      JsonNode output,
      String childSessionId,
      String childRunId,
      boolean sideEffectCommitted,
      String error,
      List<String> predecessorIds) {
    public NodeState {
      predecessorIds = predecessorIds == null ? List.of() : List.copyOf(predecessorIds);
    }
  }
}
