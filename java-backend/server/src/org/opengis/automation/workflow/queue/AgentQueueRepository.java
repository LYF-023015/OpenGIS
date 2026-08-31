/** 文件职责：workflow 后端领域：管理状态或持久化数据。 */
package org.opengis.automation.workflow.queue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.opengis.core.persistence.SessionStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Queue persistence in sessions.json/inbox, preserving the existing renderer and Python shape. */
public final class AgentQueueRepository {
  private final Path workspace;
  private final SessionStore sessions;
  private final ObjectMapper mapper = new ObjectMapper();

  public AgentQueueRepository(Path workspace) {
    this.workspace = workspace.toAbsolutePath().normalize();
    sessions = new SessionStore(this.workspace);
  }

  public synchronized void save(AgentQueueItem item) {
    sessions.putInboxItem(item.id(), toJson(item));
  }

  public synchronized Optional<AgentQueueItem> get(String id) {
    JsonNode node = sessions.load().path("inbox").path(id);
    return node.isObject() ? Optional.of(fromJson(node)) : Optional.empty();
  }

  public synchronized List<AgentQueueItem> list(String status, int limit) {
    List<AgentQueueItem> result = new ArrayList<>();
    for (JsonNode node : sessions.load().path("inbox")) {
      if (!node.isObject() || !node.has("metadata") || !node.path("metadata").has("queue_id"))
        continue;
      AgentQueueItem item = fromJson(node);
      if (status == null || status.equals(item.status().wire())) result.add(item);
    }
    result.sort(Comparator.comparingLong(AgentQueueItem::createdAt).reversed());
    return result.stream().limit(Math.max(1, Math.min(limit, 200))).toList();
  }

  private ObjectNode toJson(AgentQueueItem item) {
    ObjectNode node = mapper.createObjectNode();
    node.put("id", item.id());
    node.put("prompt", item.prompt());
    node.put("conversation_id", item.conversationId());
    node.put("profile_name", item.profileName());
    node.put("session_id", item.conversationId());
    if (item.runId() == null || item.runId().isBlank()) node.putNull("run_id");
    else node.put("run_id", item.runId());
    node.put("status", item.status().wire());
    node.put("error", item.error());
    node.put("created_at", item.createdAt() / 1000.0);
    node.put("updated_at", item.updatedAt() / 1000.0);
    ObjectNode metadata = mapper.valueToTree(item.metadata());
    metadata.put("queue_id", item.id());
    metadata.put("workspace_path", item.workspace().toString());
    metadata.put("connection_id", item.connectionId());
    metadata.put("attempts", item.attempts());
    if (item.workflowId() != null && !item.workflowId().isBlank())
      metadata.put("workflow_id", item.workflowId());
    node.set("metadata", metadata);
    return node;
  }

  private AgentQueueItem fromJson(JsonNode node) {
    JsonNode metadataNode = node.path("metadata");
    Map<String, JsonNode> metadata = new java.util.LinkedHashMap<>();
    if (metadataNode.isObject()) {
      metadataNode.properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
    }
    return new AgentQueueItem(
        node.path("id").asString(),
        node.path("prompt").asString(),
        workspace,
        node.path("conversation_id").asString(),
        node.path("profile_name").asString("gis-build"),
        metadataNode.path("connection_id").asString(),
        metadataNode.path("workflow_id").asString(),
        QueueStatus.fromWire(node.path("status").asString("queued")),
        node.path("run_id").asString(),
        node.path("error").asString(),
        metadataNode.path("attempts").asInt(0),
        Math.round(node.path("created_at").asDouble(System.currentTimeMillis() / 1000.0) * 1000),
        Math.round(node.path("updated_at").asDouble(System.currentTimeMillis() / 1000.0) * 1000),
        Map.copyOf(metadata));
  }
}
