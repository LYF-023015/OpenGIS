/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.loop;

public enum StopReason {
  COMPLETED,
  CANCELLED,
  PROVIDER_TIMEOUT,
  TOOL_TIMEOUT,
  STEP_LIMIT,
  REPEATED_FAILURE,
  DEVIATION,
  ERROR
}
