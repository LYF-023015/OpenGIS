/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.loop;

/** Controlled loop termination with a stable reason for telemetry and RPC. */
public final class AgentStopException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final StopReason reason;

  public AgentStopException(StopReason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public AgentStopException(StopReason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public StopReason reason() {
    return reason;
  }
}
