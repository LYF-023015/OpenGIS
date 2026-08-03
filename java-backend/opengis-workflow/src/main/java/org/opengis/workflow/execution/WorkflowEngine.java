package org.opengis.workflow.execution;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opengis.workflow.model.WorkflowCondition;
import org.opengis.workflow.model.WorkflowDocument;
import org.opengis.workflow.model.WorkflowEdge;
import org.opengis.workflow.model.WorkflowNode;
import org.opengis.workflow.validation.SafeConditionEvaluator;
import org.opengis.workflow.validation.WorkflowValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic DAG executor with child sessions, persisted retries, cancellation, and resume. */
public final class WorkflowEngine {
  private final ObjectMapper mapper;
  private final WorkflowValidator validator = new WorkflowValidator();
  private final SafeConditionEvaluator conditions = new SafeConditionEvaluator();

  public WorkflowEngine(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public WorkflowRunSnapshot execute(
      Path workspace,
      WorkflowDocument workflow,
      String runId,
      WorkflowNodeRunner runner,
      AtomicBoolean cancelled,
      WorkflowEventSink events,
      boolean resume) {
    validator.requireValid(workflow);
    WorkflowRunStore store = new WorkflowRunStore(workspace);
    WorkflowRunSnapshot previous = resume ? store.load(runId).orElse(null) : null;
    String createdAt = previous == null ? OffsetDateTime.now().toString() : previous.createdAt();
    Map<String, WorkflowRunSnapshot.NodeState> states =
        previous == null ? new LinkedHashMap<>() : new LinkedHashMap<>(previous.nodes());
    WorkflowRunSnapshot current = snapshot(runId, workflow.id(), "running", createdAt, "", states);
    store.save(current);
    events.emit(
        "workflow.started",
        Map.of("run_id", runId, "workflow_id", workflow.id(), "resumed", resume));
    try {
      for (WorkflowNode node : validator.topologicalOrder(workflow)) {
        if (cancelled.get()) {
          current = snapshot(runId, workflow.id(), "cancelled", createdAt, "cancelled", states);
          store.save(current);
          events.emit("workflow.cancelled", Map.of("run_id", runId));
          return current;
        }
        List<String> predecessors = predecessors(node.id(), workflow.edges());
        Map<String, JsonNode> inputs = predecessorOutputs(node.id(), workflow.edges(), states);
        String fingerprint = fingerprint(node, inputs);
        WorkflowRunSnapshot.NodeState prior = states.get(node.id());
        if (resume
            && prior != null
            && ("completed".equals(prior.status()) || "skipped".equals(prior.status()))
            && fingerprint.equals(prior.inputFingerprint())) {
          events.emit(
              "workflow.node_skipped",
              Map.of("run_id", runId, "node_id", node.id(), "reason", "already_completed"));
          continue;
        }
        String childSessionId = runId + ":" + node.id();
        if (resume
            && prior != null
            && "failed".equals(prior.status())
            && prior.sideEffectCommitted()) {
          current =
              snapshot(
                  runId,
                  workflow.id(),
                  "failed",
                  createdAt,
                  "manual retry required for node " + node.id() + " after a committed side effect",
                  states);
          store.save(current);
          return current;
        }
        int startAttempt = 1;
        boolean completed = false;
        String lastError = "";
        int attempts = startAttempt - 1;
        for (int attempt = startAttempt; attempt <= node.retryPolicy().maxAttempts(); attempt++) {
          attempts = attempt;
          events.emit(
              "workflow.node_started",
              Map.of(
                  "run_id",
                  runId,
                  "node_id",
                  node.id(),
                  "attempt",
                  attempt,
                  "child_session_id",
                  childSessionId));
          WorkflowNodeRunner.NodeResult result =
              runner.run(
                  new WorkflowNodeRunner.NodeRequest(
                      workspace, runId, childSessionId, node, inputs, attempt, cancelled::get));
          if (cancelled.get()) {
            current = snapshot(runId, workflow.id(), "cancelled", createdAt, "cancelled", states);
            store.save(current);
            return current;
          }
          lastError = result.error() == null ? "" : result.error();
          ConditionDecision decision =
              "completed".equals(result.status())
                  ? conditionDecision(node.conditions(), result.output(), inputs)
                  : new ConditionDecision("retry", lastError);
          JsonNode nodeOutput = result.output() == null ? mapper.nullNode() : result.output();
          if ("pass".equals(decision.action()) || "skip".equals(decision.action())) {
            String nodeStatus = "skip".equals(decision.action()) ? "skipped" : "completed";
            states.put(
                node.id(),
                new WorkflowRunSnapshot.NodeState(
                    node.id(),
                    nodeStatus,
                    attempt,
                    fingerprint,
                    nodeOutput,
                    childSessionId,
                    result.childRunId(),
                    result.sideEffectCommitted(),
                    "",
                    predecessors));
            store.save(snapshot(runId, workflow.id(), "running", createdAt, "", states));
            events.emit(
                "skip".equals(decision.action())
                    ? "workflow.node_skipped"
                    : "workflow.node_completed",
                Map.of(
                    "run_id",
                    runId,
                    "node_id",
                    node.id(),
                    "attempt",
                    attempt,
                    "child_session_id",
                    childSessionId));
            completed = true;
            break;
          }
          lastError = decision.error();
          if (lastError.isBlank()) lastError = "node result or condition failed";
          states.put(
              node.id(),
              new WorkflowRunSnapshot.NodeState(
                  node.id(),
                  "failed",
                  attempt,
                  fingerprint,
                  nodeOutput,
                  childSessionId,
                  result.childRunId(),
                  result.sideEffectCommitted(),
                  lastError,
                  predecessors));
          store.save(snapshot(runId, workflow.id(), "running", createdAt, "", states));
          if (result.sideEffectCommitted()) {
            lastError = "manual retry required after committed side effect: " + lastError;
            break;
          }
          if ("fail".equals(decision.action())) break;
          if (attempt < node.retryPolicy().maxAttempts() && node.retryPolicy().backoffMs() > 0) {
            sleep(node.retryPolicy().backoffMs(), cancelled);
          }
        }
        if (!completed) {
          current =
              snapshot(
                  runId,
                  workflow.id(),
                  "failed",
                  createdAt,
                  "node " + node.id() + " failed after " + attempts + " attempt(s): " + lastError,
                  states);
          store.save(current);
          events.emit(
              "workflow.failed",
              Map.of("run_id", runId, "node_id", node.id(), "error", current.error()));
          return current;
        }
      }
      current = snapshot(runId, workflow.id(), "completed", createdAt, "", states);
      store.save(current);
      events.emit("workflow.completed", Map.of("run_id", runId, "workflow_id", workflow.id()));
      return current;
    } catch (RuntimeException exception) {
      String error =
          exception.getMessage() == null
              ? exception.getClass().getSimpleName()
              : exception.getMessage();
      current = snapshot(runId, workflow.id(), "failed", createdAt, error, states);
      store.save(current);
      events.emit("workflow.failed", Map.of("run_id", runId, "error", error));
      return current;
    }
  }

  private ConditionDecision conditionDecision(
      List<WorkflowCondition> declared, JsonNode output, Map<String, JsonNode> inputs) {
    if (declared.isEmpty()) return new ConditionDecision("pass", "");
    Map<String, JsonNode> variables = new LinkedHashMap<>(inputs);
    variables.put("output", output == null ? mapper.nullNode() : output);
    for (WorkflowCondition condition : declared) {
      if (!conditions.evaluate(condition.expression(), variables)) {
        String action = condition.onFalse() == null ? "fail" : condition.onFalse();
        return new ConditionDecision(
            action,
            condition.description() == null || condition.description().isBlank()
                ? "workflow condition failed"
                : condition.description());
      }
    }
    return new ConditionDecision("pass", "");
  }

  private String fingerprint(WorkflowNode node, Map<String, JsonNode> inputs) {
    try {
      byte[] bytes = mapper.writeValueAsBytes(Map.of("node", node, "inputs", inputs));
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot fingerprint workflow node", exception);
    }
  }

  private static Map<String, JsonNode> predecessorOutputs(
      String nodeId, List<WorkflowEdge> edges, Map<String, WorkflowRunSnapshot.NodeState> states) {
    Map<String, JsonNode> outputs = new LinkedHashMap<>();
    for (WorkflowEdge edge :
        edges.stream().filter(value -> nodeId.equals(value.target())).toList()) {
      WorkflowRunSnapshot.NodeState state = states.get(edge.source());
      if (state == null
          || !("completed".equals(state.status()) || "skipped".equals(state.status()))) {
        throw new IllegalStateException("Predecessor is not completed: " + edge.source());
      }
      JsonNode value = state.output();
      if (edge.sourceHandle() != null
          && !edge.sourceHandle().isBlank()
          && value != null
          && value.isObject()
          && value.has(edge.sourceHandle())) {
        value = value.path(edge.sourceHandle());
      }
      String inputName =
          edge.targetHandle() == null || edge.targetHandle().isBlank()
              ? edge.source()
              : edge.targetHandle();
      if (outputs.putIfAbsent(inputName, value) != null) {
        throw new IllegalStateException("Multiple edges map to input: " + inputName);
      }
    }
    return Map.copyOf(outputs);
  }

  private static List<String> predecessors(String nodeId, List<WorkflowEdge> edges) {
    List<String> result = new ArrayList<>();
    edges.stream()
        .filter(edge -> nodeId.equals(edge.target()))
        .map(WorkflowEdge::source)
        .forEach(result::add);
    return List.copyOf(result);
  }

  private static WorkflowRunSnapshot snapshot(
      String runId,
      String workflowId,
      String status,
      String createdAt,
      String error,
      Map<String, WorkflowRunSnapshot.NodeState> states) {
    return new WorkflowRunSnapshot(
        runId,
        workflowId,
        status,
        createdAt,
        OffsetDateTime.now().toString(),
        error == null ? "" : error,
        new LinkedHashMap<>(states));
  }

  private static void sleep(long milliseconds, AtomicBoolean cancelled) {
    long remaining = milliseconds;
    while (remaining > 0 && !cancelled.get()) {
      long slice = Math.min(remaining, 100);
      try {
        Thread.sleep(slice);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        cancelled.set(true);
        return;
      }
      remaining -= slice;
    }
  }

  private record ConditionDecision(String action, String error) {}
}
