/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.model;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** One DAG node with a structured execution reference and declarative conditions. */
public record WorkflowNode(
    String id,
    String title,
    String description,
    String type,
    ExecutionReference execution,
    List<WorkflowPort> inputs,
    List<WorkflowPort> outputs,
    Map<String, JsonNode> params,
    JsonNode position,
    String inputContract,
    String outputContract,
    List<WorkflowCondition> conditions,
    RetryPolicy retryPolicy,
    String notes) {
  public static final List<String> TYPES =
      List.of("agent_task", "tool_call", "operation", "java_script", "subworkflow");

  public WorkflowNode {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
    params = params == null ? Map.of() : Map.copyOf(params);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
    retryPolicy = retryPolicy == null ? RetryPolicy.defaults() : retryPolicy;
  }

  public record ExecutionReference(String kind, String ref) {}

  public record RetryPolicy(int maxAttempts, long backoffMs) {
    public RetryPolicy {
      maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
      backoffMs = Math.max(0, Math.min(backoffMs, 60_000));
    }

    public static RetryPolicy defaults() {
      return new RetryPolicy(1, 0);
    }
  }
}
