/** 文件职责：script 后端领域：实现该文件名所对应的单一职责。 */
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
