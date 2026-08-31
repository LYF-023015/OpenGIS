/** 文件职责：workflow 后端领域：承载该领域的核心业务流程。 */
package org.opengis.automation.workflow.execution;

import java.nio.file.Path;
import java.util.Map;
import org.opengis.automation.workflow.model.WorkflowNode;
import tools.jackson.databind.JsonNode;

/**
 * Port implemented by the server to run a node as a child Agent session or structured tool call.
 */
@FunctionalInterface
public interface WorkflowNodeRunner {
  NodeResult run(NodeRequest request);

  record NodeRequest(
      Path workspace,
      String workflowRunId,
      String childSessionId,
      WorkflowNode node,
      Map<String, JsonNode> predecessorOutputs,
      int attempt,
      Cancellation cancellation) {}

  record NodeResult(
      String status,
      JsonNode output,
      String childRunId,
      String error,
      boolean sideEffectCommitted) {
    public static NodeResult completed(
        JsonNode output, String childRunId, boolean sideEffectCommitted) {
      return new NodeResult("completed", output, childRunId, "", sideEffectCommitted);
    }

    public static NodeResult failed(String error, String childRunId) {
      return new NodeResult("failed", null, childRunId, error, false);
    }
  }

  @FunctionalInterface
  interface Cancellation {
    boolean isCancelled();
  }
}
