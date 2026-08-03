package org.opengis.worker;

import java.util.Map;

/** Receives worker status, log and allowlisted dynamic-map events. */
@FunctionalInterface
public interface WorkerEventSink {
  void emit(String method, Map<String, Object> parameters);

  static WorkerEventSink noop() {
    return (method, parameters) -> {};
  }
}
