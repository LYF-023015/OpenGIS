/** 文件职责：worker 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.worker;

import java.util.Map;

/** Receives worker status, log and allowlisted dynamic-map events. */
@FunctionalInterface
public interface WorkerEventSink {
  void emit(String method, Map<String, Object> parameters);

  static WorkerEventSink noop() {
    return (method, parameters) -> {};
  }
}
