package org.opengis.agent.loop;

import org.opengis.ai.model.LlmUsage;

public record AgentLoopResult(
    String status,
    StopReason stopReason,
    String finalAnswer,
    String error,
    int providerTurns,
    int toolSteps,
    LlmUsage usage) {
  public boolean completed() {
    return stopReason == StopReason.COMPLETED;
  }
}
