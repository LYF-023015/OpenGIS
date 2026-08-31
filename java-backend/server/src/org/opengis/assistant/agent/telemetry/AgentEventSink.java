/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.telemetry;

@FunctionalInterface
public interface AgentEventSink {
  void emit(AgentEvent event);

  static AgentEventSink noop() {
    return ignored -> {};
  }
}
