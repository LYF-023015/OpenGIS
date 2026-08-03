package org.opengis.workflow.queue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.JsonNode;

/** Durable queue state machine with one processor per normalized workspace. */
public final class AgentQueueService {
  private final QueueRunPort runs;
  private final Map<Path, ReentrantLock> processors = new ConcurrentHashMap<>();

  public AgentQueueService(QueueRunPort runs) {
    this.runs = runs;
  }

  public AgentQueueItem submit(
      Path workspace,
      String prompt,
      String conversationId,
      String profileName,
      String connectionId,
      String workflowId,
      Map<String, JsonNode> metadata) {
    Path root = workspace.toAbsolutePath().normalize();
    JsonNode idempotency = metadata == null ? null : metadata.get("idempotency_key");
    if (idempotency != null && idempotency.isTextual() && !idempotency.asText().isBlank()) {
      for (AgentQueueItem existing : new AgentQueueRepository(root).list(null, 200)) {
        JsonNode existingKey = existing.metadata().get("idempotency_key");
        if (existingKey != null && idempotency.asText().equals(existingKey.asText())) {
          if (!existing.prompt().equals(prompt)) {
            throw new IllegalArgumentException(
                "idempotency_key already belongs to a different command");
          }
          return existing;
        }
      }
    }
    long now = System.currentTimeMillis();
    AgentQueueItem item =
        new AgentQueueItem(
            UUID.randomUUID().toString().replace("-", ""),
            prompt,
            root,
            conversationId,
            profileName == null || profileName.isBlank() ? "gis-build" : profileName,
            connectionId == null ? "" : connectionId,
            workflowId == null ? "" : workflowId,
            QueueStatus.QUEUED,
            "",
            "",
            0,
            now,
            now,
            metadata);
    new AgentQueueRepository(root).save(item);
    return item;
  }

  public AgentQueueItem get(Path workspace, String id) {
    AgentQueueRepository repository = new AgentQueueRepository(workspace);
    AgentQueueItem item = repository.get(id).orElse(null);
    return item == null ? null : reconcile(repository, item);
  }

  public List<AgentQueueItem> list(Path workspace, String status, int limit) {
    AgentQueueRepository repository = new AgentQueueRepository(workspace);
    return repository.list(status, limit).stream()
        .map(item -> reconcile(repository, item))
        .toList();
  }

  public AgentQueueItem run(Path workspace, String id, boolean resume) {
    AgentQueueRepository repository = new AgentQueueRepository(workspace);
    AgentQueueItem item = repository.get(id).orElse(null);
    if (item == null) return null;
    if (item.status() != QueueStatus.QUEUED) return item;
    QueueRunPort.StartResult start = runs.start(item, resume);
    AgentQueueItem updated =
        start.started()
            ? item.withState(QueueStatus.RUNNING, start.runId(), "", item.attempts() + 1)
            : item.withState(QueueStatus.ERROR, item.runId(), start.error(), item.attempts() + 1);
    repository.save(updated);
    return updated;
  }

  public AgentQueueItem retry(Path workspace, String id) {
    AgentQueueRepository repository = new AgentQueueRepository(workspace);
    AgentQueueItem item = repository.get(id).orElse(null);
    if (item == null
        || (item.status() != QueueStatus.ERROR && item.status() != QueueStatus.CANCELLED))
      return item;
    AgentQueueItem updated = item.withState(QueueStatus.QUEUED, item.runId(), "", item.attempts());
    repository.save(updated);
    return updated;
  }

  public AgentQueueItem cancel(Path workspace, String id) {
    AgentQueueRepository repository = new AgentQueueRepository(workspace);
    AgentQueueItem item = repository.get(id).orElse(null);
    if (item == null) return null;
    if (item.status() == QueueStatus.RUNNING) runs.cancel(item);
    if (item.status() != QueueStatus.QUEUED && item.status() != QueueStatus.RUNNING) return item;
    AgentQueueItem updated =
        item.withState(QueueStatus.CANCELLED, item.runId(), "cancelled", item.attempts());
    repository.save(updated);
    return updated;
  }

  public List<AgentQueueItem> resume(Path workspace, int limit) {
    AgentQueueRepository repository = new AgentQueueRepository(workspace);
    List<AgentQueueItem> result = new ArrayList<>();
    for (AgentQueueItem item : repository.list(null, limit)) {
      AgentQueueItem reconciled = reconcile(repository, item);
      if (reconciled.status() == QueueStatus.RUNNING && "unknown".equals(runs.status(reconciled))) {
        reconciled =
            reconciled.withState(
                QueueStatus.ERROR,
                reconciled.runId(),
                "Recovered from an interrupted running state.",
                reconciled.attempts());
        repository.save(reconciled);
      }
      if (reconciled.status() == QueueStatus.QUEUED
          || reconciled.status() == QueueStatus.ERROR
          || reconciled.status() == QueueStatus.CANCELLED) result.add(reconciled);
    }
    return List.copyOf(result);
  }

  public List<AgentQueueItem> process(Path workspace, int limit) {
    Path root = workspace.toAbsolutePath().normalize();
    ReentrantLock lock = processors.computeIfAbsent(root, ignored -> new ReentrantLock());
    if (!lock.tryLock()) return List.of();
    try {
      List<AgentQueueItem> processed = new ArrayList<>();
      for (int i = 0; i < Math.max(1, limit); i++) {
        AgentQueueItem next =
            new AgentQueueRepository(root)
                .list("queued", 200).stream()
                    .min(java.util.Comparator.comparingLong(AgentQueueItem::createdAt))
                    .orElse(null);
        if (next == null) break;
        processed.add(run(root, next.id(), false));
      }
      return List.copyOf(processed);
    } finally {
      lock.unlock();
    }
  }

  private AgentQueueItem reconcile(AgentQueueRepository repository, AgentQueueItem item) {
    if (item.status() != QueueStatus.RUNNING) return item;
    String actual = runs.status(item);
    QueueStatus terminal =
        switch (actual) {
          case "success", "completed" -> QueueStatus.SUCCESS;
          case "error", "failed" -> QueueStatus.ERROR;
          case "cancelled" -> QueueStatus.CANCELLED;
          default -> null;
        };
    if (terminal == null) return item;
    AgentQueueItem updated =
        item.withState(
            terminal,
            item.runId(),
            terminal == QueueStatus.ERROR ? "run failed" : "",
            item.attempts());
    repository.save(updated);
    return updated;
  }
}
