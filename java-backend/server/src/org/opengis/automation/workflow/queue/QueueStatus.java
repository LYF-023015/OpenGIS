/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.queue;

/** Durable queue states compatible with the Python control-plane contract. */
public enum QueueStatus {
  QUEUED("queued"),
  RUNNING("running"),
  SUCCESS("success"),
  ERROR("error"),
  CANCELLED("cancelled");

  private final String wire;

  QueueStatus(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public static QueueStatus fromWire(String value) {
    for (QueueStatus status : values()) if (status.wire.equals(value)) return status;
    throw new IllegalArgumentException("Unknown queue status: " + value);
  }
}
