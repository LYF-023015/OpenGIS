package org.opengis.agent.telemetry;

@FunctionalInterface
public interface AgentEventSink {
  void emit(AgentEvent event);

  static AgentEventSink noop() {
    return ignored -> {};
  }
}
