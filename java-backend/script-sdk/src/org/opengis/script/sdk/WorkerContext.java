/** 文件职责：script 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.script.sdk;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Resident-worker view over the same audited clients used by one-off scripts. */
public final class WorkerContext {
  private final ScriptContext script;
  private final Map<String, Object> config;
  private final DynamicMapEmitter dynamicMap;

  public WorkerContext(
      Path workspace, String workerId, Map<String, Object> config, ProtocolTransport transport) {
    this.script = new ScriptContext(workspace, workerId, transport);
    this.config = config == null ? Map.of() : Map.copyOf(config);
    this.dynamicMap = new DynamicMapEmitter(script.map());
  }

  public Path workspace() {
    return script.workspace();
  }

  public String workerId() {
    return script.runId();
  }

  public Map<String, Object> config() {
    return config;
  }

  public ToolClient tools() {
    return script.tools();
  }

  public ArtifactClient artifacts() {
    return script.artifacts();
  }

  public ProgressEmitter progress() {
    return script.progress();
  }

  public DynamicMapEmitter dynamicMap() {
    return dynamicMap;
  }

  public void checkCancelled() {
    script.checkCancelled();
  }

  public void sleep(Duration duration) throws InterruptedException {
    long remaining = duration.toMillis();
    while (remaining > 0) {
      checkCancelled();
      long step = Math.min(remaining, 250);
      Thread.sleep(step);
      remaining -= step;
    }
  }
}
