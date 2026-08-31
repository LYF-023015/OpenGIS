/** 文件职责：server 后端领域：接收外部请求并调用应用服务。 */
package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.opengis.core.protocol.JsonRpcErrorCodes;
import org.opengis.server.workflow.WorkflowApplicationService;
import org.opengis.automation.workflow.WorkflowCodec;
import org.opengis.automation.workflow.migration.WorkflowMigrationReport;
import org.opengis.automation.workflow.migration.WorkflowMigrationService;
import org.opengis.automation.workflow.model.WorkflowDocument;
import org.opengis.automation.workflow.persistence.WorkflowStore;
import org.opengis.automation.workflow.queue.AgentQueueItem;
import org.opengis.automation.workflow.queue.AgentQueueService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Phase 6 Queue and Workflow v2 RPC surface. */
@Component
public final class WorkflowRpcMethods {
  private final RpcMethodRegistry registry;
  private final AgentQueueService queue;
  private final WorkflowApplicationService workflows;
  private final ObjectMapper mapper;
  private final AtomicReference<Path> lastWorkspace = new AtomicReference<>();

  public WorkflowRpcMethods(
      RpcMethodRegistry registry,
      AgentQueueService queue,
      WorkflowApplicationService workflows,
      ObjectMapper mapper) {
    this.registry = registry;
    this.queue = queue;
    this.workflows = workflows;
    this.mapper = mapper;
  }

  @PostConstruct
  void registerMethods() {
    registry.registerOrReplace("rpc.agent.queue.submit", this::submit);
    registry.registerOrReplace("rpc.agent.queue.run", this::run);
    registry.registerOrReplace("rpc.agent.queue.get", this::get);
    registry.registerOrReplace("rpc.agent.queue.resume", this::resume);
    registry.registerOrReplace("rpc.agent.queue.retry", this::retry);
    registry.registerOrReplace("rpc.agent.queue.cancel", this::cancel);
    registry.registerOrReplace("rpc.agent.queue.process", this::process);
    registry.registerOrReplace("rpc.agent.queue.list", this::list);
    registry.registerOrReplace("rpc.workflow.inspect", this::inspect);
    registry.registerOrReplace("rpc.workflow.convert", this::convert);
    registry.registerOrReplace("rpc.workflow.load", this::load);
    registry.registerOrReplace("rpc.workflow.save", this::save);
    registry.registerOrReplace("rpc.workflow.run", this::runWorkflow);
    registry.registerOrReplace("rpc.workflow.get", this::getWorkflowRun);
    registry.registerOrReplace("rpc.workflow.cancel", this::cancelWorkflow);
  }

  private Object submit(JsonNode params) {
    Path workspace = workspace(params, true);
    String message = text(params, "message", text(params, "prompt", ""));
    String conversationId = text(params, "conversation_id", "");
    if (message.isBlank() || conversationId.isBlank())
      throw invalid("message and conversation_id are required");
    Map<String, JsonNode> metadata = new LinkedHashMap<>();
    if (params.path("metadata").isObject()) {
      params
          .path("metadata")
          .properties()
          .forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
    }
    AgentQueueItem item =
        queue.submit(
            workspace,
            message,
            conversationId,
            text(params, "agent_profile", text(params, "profile_name", "gis-build")),
            text(params, "_connection_id", ""),
            text(params, "workflow_id", ""),
            metadata);
    return Map.of(
        "status", "queued", "queue_id", item.id(), "inbox_id", item.id(), "item", rpcItem(item));
  }

  private Object run(JsonNode params) {
    AgentQueueItem item =
        queue.run(workspace(params, true), queueId(params), params.path("resume").asBoolean(false));
    return item == null
        ? Map.of("status", "not_found")
        : Map.of("status", item.status().wire(), "item", rpcItem(item));
  }

  private Object get(JsonNode params) {
    AgentQueueItem item = queue.get(workspace(params, true), queueId(params));
    return item == null
        ? Map.of("status", "not_found")
        : Map.of("status", "ok", "item", rpcItem(item));
  }

  private Object resume(JsonNode params) {
    List<Map<String, Object>> items =
        queue.resume(workspace(params, true), limit(params)).stream().map(this::rpcItem).toList();
    return Map.of("status", "ok", "items", items);
  }

  private Object retry(JsonNode params) {
    AgentQueueItem item = queue.retry(workspace(params, true), queueId(params));
    return item == null
        ? Map.of("status", "not_found")
        : Map.of("status", item.status().wire(), "item", rpcItem(item));
  }

  private Object cancel(JsonNode params) {
    AgentQueueItem item = queue.cancel(workspace(params, true), queueId(params));
    return item == null
        ? Map.of("status", "not_found")
        : Map.of("status", item.status().wire(), "item", rpcItem(item));
  }

  private Object process(JsonNode params) {
    List<Map<String, Object>> items =
        queue.process(workspace(params, true), limit(params)).stream().map(this::rpcItem).toList();
    return Map.of("status", items.isEmpty() ? "ok" : "started", "processed", items);
  }

  private Object list(JsonNode params) {
    String status = params.path("status").isString() ? params.path("status").asString() : null;
    List<Map<String, Object>> items =
        queue.list(workspace(params, false), status, limit(params)).stream()
            .map(this::rpcItem)
            .toList();
    return Map.of("items", items);
  }

  private Object inspect(JsonNode params) {
    return new WorkflowMigrationService(mapper).inspect(rawWorkflow(params));
  }

  private Object convert(JsonNode params) {
    Path workspace = workspace(params, true);
    WorkflowMigrationReport report =
        new WorkflowMigrationService(mapper).inspect(rawWorkflow(params));
    if ("converted".equals(report.status()) && params.path("save").asBoolean(false)) {
      WorkflowDocument document = new WorkflowCodec(mapper).parse(report.convertedWorkflow());
      new WorkflowStore(workspace).save(document);
    }
    return report;
  }

  private Object load(JsonNode params) {
    Path workspace = workspace(params, true);
    String id = text(params, "workflow_id", "");
    return new WorkflowStore(workspace)
        .loadDocument(id)
        .<Object>map(document -> Map.of("status", "ok", "workflow", document))
        .orElseGet(() -> Map.of("status", "not_found"));
  }

  private Object save(JsonNode params) {
    Path workspace = workspace(params, true);
    JsonNode value = params.path("workflow");
    WorkflowDocument document = new WorkflowCodec(mapper).parse(value);
    Path path = new WorkflowStore(workspace).save(document);
    return Map.of("status", "ok", "workflow_id", document.id(), "workflow_path", path.toString());
  }

  private Object runWorkflow(JsonNode params) {
    Path workspace = workspace(params, true);
    String id = text(params, "workflow_id", "");
    WorkflowDocument workflow =
        new WorkflowStore(workspace)
            .loadDocument(id)
            .orElseThrow(() -> invalid("workflow not found: " + id));
    return workflows.start(
        workspace,
        workflow,
        text(params, "conversation_id", "workflow-" + id),
        text(params, "_connection_id", ""),
        params.path("resume").asBoolean(false),
        text(params, "run_id", ""));
  }

  private Object getWorkflowRun(JsonNode params) {
    Path workspace = workspace(params, true);
    String runId = text(params, "run_id", "");
    return new org.opengis.automation.workflow.execution.WorkflowRunStore(workspace)
        .load(runId)
        .<Object>map(snapshot -> Map.of("status", "ok", "run", snapshot))
        .orElseGet(() -> Map.of("status", "not_found"));
  }

  private Object cancelWorkflow(JsonNode params) {
    boolean cancelled = workflows.cancel(workspace(params, true), text(params, "run_id", ""));
    return Map.of("status", cancelled ? "cancelled" : "not_found");
  }

  private JsonNode rawWorkflow(JsonNode params) {
    JsonNode workflow = params.get("workflow");
    if (workflow != null && workflow.isObject()) return workflow;
    if (params.path("raw").isString()) {
      try {
        return mapper.readTree(params.path("raw").asString());
      } catch (Exception exception) {
        throw invalid("invalid workflow JSON");
      }
    }
    String pathValue = text(params, "workflow_path", "");
    if (pathValue.isBlank()) throw invalid("workflow, raw, or workflow_path is required");
    Path workspace = workspace(params, true);
    Path path = Path.of(pathValue).toAbsolutePath().normalize();
    if (!path.startsWith(workspace) || !Files.isRegularFile(path))
      throw invalid("workflow_path must be a file inside workspace");
    try {
      return mapper.readTree(Files.readString(path));
    } catch (Exception exception) {
      throw invalid("cannot read workflow file");
    }
  }

  private Map<String, Object> rpcItem(AgentQueueItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", item.id());
    value.put("inbox_id", item.id());
    value.put("status", item.status().wire());
    value.put("run_id", item.runId());
    value.put("workspace_path", item.workspace().toString());
    value.put("profile_name", item.profileName());
    value.put("conversation_id", item.conversationId());
    value.put("error", item.error());
    value.put("created_at", item.createdAt() / 1000.0);
    value.put("updated_at", item.updatedAt() / 1000.0);
    value.put("metadata", item.metadata());
    return value;
  }

  private Path workspace(JsonNode params, boolean required) {
    String value = text(params, "workspace_path", "");
    if (!value.isBlank()) {
      Path path = Path.of(value).toAbsolutePath().normalize();
      lastWorkspace.set(path);
      return path;
    }
    Path fallback = lastWorkspace.get();
    if (fallback != null) return fallback;
    if (required) throw invalid("workspace_path is required");
    throw invalid("workspace_path is required before queue.list");
  }

  private static String queueId(JsonNode params) {
    String id = text(params, "queue_id", text(params, "inbox_id", ""));
    if (id.isBlank()) throw invalid("queue_id or inbox_id is required");
    return id;
  }

  private static int limit(JsonNode params) {
    return Math.max(1, Math.min(params.path("limit").asInt(100), 200));
  }

  private static String text(JsonNode params, String field, String fallback) {
    return params.path(field).isString() ? params.path(field).asString() : fallback;
  }

  private static RpcException invalid(String message) {
    return new RpcException(JsonRpcErrorCodes.INVALID_PARAMS, message);
  }
}
