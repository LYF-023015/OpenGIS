package org.opengis.script.sdk;

import java.util.Map;

/** Resident worker SPI. start normally blocks until cancellation or service completion. */
public interface OpenGisWorker {
  void start(WorkerContext context) throws Exception;

  void stop() throws Exception;

  default Map<String, Object> health() {
    return Map.of("status", "running");
  }
}
