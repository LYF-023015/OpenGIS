/** 文件职责：workflow 后端领域：验证对应功能的行为与边界。 */
package org.opengis.automation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.automation.workflow.queue.AgentQueueItem;
import org.opengis.automation.workflow.queue.AgentQueueService;
import org.opengis.automation.workflow.queue.QueueRunPort;
import tools.jackson.databind.ObjectMapper;

class AgentQueueScenarioTest {
  @TempDir Path workspace;

  @Test
  void submitRunReconcileRetryCancelAndResumeAreDurable() {
    FakeRuns runs = new FakeRuns();
    AgentQueueService service = new AgentQueueService(runs);
    AgentQueueItem submitted =
        service.submit(workspace, "map", "chat", "gis-build", "connection", "", Map.of());
    assertThat(service.list(workspace, "queued", 10)).hasSize(1);

    AgentQueueItem running = service.run(workspace, submitted.id(), false);
    assertThat(running.status().wire()).isEqualTo("running");
    runs.statuses.put(running.runId(), "completed");
    assertThat(service.get(workspace, submitted.id()).status().wire()).isEqualTo("success");

    AgentQueueItem second =
        service.submit(workspace, "fail", "chat-2", "gis-build", "", "", Map.of());
    AgentQueueItem runningSecond = service.run(workspace, second.id(), false);
    runs.statuses.put(runningSecond.runId(), "error");
    assertThat(service.get(workspace, second.id()).status().wire()).isEqualTo("error");
    assertThat(service.retry(workspace, second.id()).status().wire()).isEqualTo("queued");
    assertThat(service.cancel(workspace, second.id()).status().wire()).isEqualTo("cancelled");

    AgentQueueService restarted = new AgentQueueService(runs);
    assertThat(restarted.resume(workspace, 10))
        .extracting(item -> item.status().wire())
        .contains("cancelled");
  }

  @Test
  void idempotencyKeyReturnsTheOriginalQueueItem() {
    AgentQueueService service = new AgentQueueService(new FakeRuns());
    Map<String, tools.jackson.databind.JsonNode> key =
        Map.of("idempotency_key", new ObjectMapper().valueToTree("client-command-1"));
    AgentQueueItem first = service.submit(workspace, "same", "chat", "gis-build", "", "", key);
    AgentQueueItem duplicate = service.submit(workspace, "same", "chat", "gis-build", "", "", key);
    assertThat(duplicate.id()).isEqualTo(first.id());
    assertThat(service.list(workspace, null, 10)).hasSize(1);
  }

  private static final class FakeRuns implements QueueRunPort {
    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private int sequence;

    @Override
    public StartResult start(AgentQueueItem item, boolean resume) {
      String runId = resume && !item.runId().isBlank() ? item.runId() : "run-" + (++sequence);
      statuses.put(runId, "running");
      return new StartResult(true, runId, "");
    }

    @Override
    public boolean cancel(AgentQueueItem item) {
      statuses.put(item.runId(), "cancelled");
      return true;
    }

    @Override
    public String status(AgentQueueItem item) {
      return statuses.getOrDefault(item.runId(), "unknown");
    }
  }
}
