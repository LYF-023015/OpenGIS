/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.execution;

import java.util.Map;

/** Event boundary used to project workflow state into Chat, Runs, Plan, and Workflow UI. */
@FunctionalInterface
public interface WorkflowEventSink {
  void emit(String event, Map<String, Object> data);

  static WorkflowEventSink noop() {
    return (event, data) -> {};
  }
}
