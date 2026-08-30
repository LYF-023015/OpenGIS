package org.opengis.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.code.runner.ScriptCallbacks;
import org.opengis.platform.persistence.JsonFileStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkerManagerTest {
  @TempDir Path workspace;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void startsEmitsDynamicMapPausesRestartsAndDeletesChildJvm() throws Exception {
    ArrayList<Map<String, Object>> mapEvents = new ArrayList<>();
    try (WorkerManager manager = new WorkerManager(mapper, 2)) {
      ObjectNode specification = mapper.createObjectNode();
      specification.put("name", "Live Java Worker");
      specification.put("entry_class", "sample.LiveWorker");
      specification.put(
          "code",
          """
          package sample;
          import java.time.Duration;
          import java.util.List;
          import java.util.Map;
          import org.opengis.script.sdk.OpenGisWorker;
          import org.opengis.script.sdk.WorkerContext;
          public final class LiveWorker implements OpenGisWorker {
            private volatile boolean stopped;
            public void start(WorkerContext context) throws Exception {
              long sequence = 1;
              context.dynamicMap().full("live", "Live", Map.of("type","FeatureCollection","features",List.of()), Map.of(), sequence++);
              while (!stopped) context.sleep(Duration.ofMillis(100));
            }
            public void stop() { stopped = true; }
          }
          """);
      ObjectNode started =
          manager.createAndStart(
              workspace,
              specification,
              ScriptCallbacks.disconnected(),
              (method, parameters) -> {
                if (method.startsWith("rpc.ui.map.")) mapEvents.add(parameters);
              });
      String id = started.path("worker_id").asString();
      ObjectNode running = waitStatus(manager, id, "running");
      assertThat(running.path("pid").asLong()).isPositive();
      assertThat(mapEvents).isNotEmpty();
      assertThat(manager.get(workspace, id, false).path("resources").path("alive").asBoolean())
          .isTrue();

      assertThat(manager.pause(workspace, id, "test").path("status").asString())
          .isEqualTo("paused");
      ObjectNode restarted =
          manager.restart(workspace, id, ScriptCallbacks.disconnected(), WorkerEventSink.noop());
      assertThat(restarted.path("status").asString()).isIn("starting", "running");
      waitStatus(manager, id, "running");
      manager.pause(workspace, id, "cleanup");
      Thread.sleep(300);
      assertThat(manager.delete(workspace, id).path("deleted").asBoolean()).isTrue();
      assertThat(workspace.resolve("worker")).isEmptyDirectory();
    }
  }

  @Test
  void restoresStaleWorkerAndCreatesPythonMigrationReport() {
    Path folder = workspace.resolve("worker/legacy-worker");
    JsonFileStore files = new JsonFileStore(mapper);
    ObjectNode metadata = mapper.createObjectNode();
    metadata.put("worker_id", "legacy-1");
    metadata.put("workspace_path", workspace.toString());
    metadata.put("status", "running");
    metadata.put("state_version", 1);
    files.write(folder.resolve("metadata.json"), metadata);
    files.writeText(
        folder.resolve("main.py"),
        "import requests\nimport os\nprint(os.getenv('API_URL'))\nprint('rpc.ui.map.dynamic_layer_update')\n");
    files.write(
        folder.resolve("manifest.json"), mapper.createObjectNode().put("runtime", "python"));

    try (WorkerManager manager = new WorkerManager(mapper)) {
      ObjectNode restored = manager.list(workspace, false);
      assertThat(restored.path("workers").get(0).path("status").asString()).isEqualTo("paused");
      assertThat(restored.path("workers").get(0).path("restored").asBoolean()).isTrue();
    }
    ObjectNode report = new WorkerMigrationService(mapper).inspect(workspace, "legacy-1");
    assertThat(report.path("status").asString()).isEqualTo("manual_migration_required");
    assertThat(report.path("uses_network").asBoolean()).isTrue();
    assertThat(report.path("uses_dynamic_map").asBoolean()).isTrue();
    assertThat(workspace.resolve(report.path("report_path").asString())).exists();
  }

  private ObjectNode waitStatus(WorkerManager manager, String id, String expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    ObjectNode value = manager.get(workspace, id, false);
    while (System.nanoTime() < deadline && !expected.equals(value.path("status").asString())) {
      Thread.sleep(50);
      value = manager.get(workspace, id, false);
    }
    assertThat(value.path("status").asString()).isEqualTo(expected);
    return value;
  }
}
