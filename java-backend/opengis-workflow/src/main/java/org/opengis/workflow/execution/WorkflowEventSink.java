package org.opengis.workflow.execution;

import java.util.Map;

/** Event boundary used to project workflow state into Chat, Runs, Plan, and Workflow UI. */
@FunctionalInterface
public interface WorkflowEventSink {
  void emit(String event, Map<String, Object> data);

  static WorkflowEventSink noop() {
    return (event, data) -> {};
  }
}
