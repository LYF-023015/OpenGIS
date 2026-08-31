/** 文件职责：agent 后端领域：定义领域数据结构与协议。 */
package org.opengis.assistant.agent.loop;

import org.opengis.assistant.model.LlmUsage;

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
