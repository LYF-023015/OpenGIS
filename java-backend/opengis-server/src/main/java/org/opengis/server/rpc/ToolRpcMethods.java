package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.common.protocol.JsonRpcErrorCodes;
import org.opengis.platform.persistence.JsonTypeReferences;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolResult;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Phase 4 executable Tool catalog and direct execution bridge. */
@Component
public class ToolRpcMethods {
  private final RpcMethodRegistry rpcMethods;
  private final ToolRegistry tools;
  private final ToolRuntime runtime;
  private final UiRpcGateway uiGateway;
  private final ObjectMapper objectMapper;

  public ToolRpcMethods(
      RpcMethodRegistry rpcMethods,
      ToolRegistry tools,
      ToolRuntime runtime,
      UiRpcGateway uiGateway,
      ObjectMapper objectMapper) {
    this.rpcMethods = rpcMethods;
    this.tools = tools;
    this.runtime = runtime;
    this.uiGateway = uiGateway;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void registerMethods() {
    rpcMethods.registerOrReplace("rpc.tool.list", this::list);
    rpcMethods.registerOrReplace("rpc.tool.execute", this::execute);
    rpcMethods.registerOrReplace("rpc.fs.load_file", this::loadFile);
    rpcMethods.registerOrReplace("rpc.fs.get_file_info", this::fileInfo);
  }

  private Object list(JsonNode params) {
    return Map.of("tools", tools.definitions().stream().map(this::legacyDefinition).toList());
  }

  private Object execute(JsonNode params) {
    if (!params.isObject() || !params.path("name").isString()) {
      throw invalidParams("name is required");
    }
    JsonNode arguments = params.path("args");
    if (arguments.isMissingNode() || arguments.isNull()) {
      arguments = objectMapper.createObjectNode();
    }
    if (!arguments.isObject()) {
      throw invalidParams("args must be an object");
    }
    Path workspace = workspace(params);
    String runId = params.path("run_id").asString("direct");
    CancellationToken cancellation = new CancellationToken();
    ToolExecutionContext context =
        new ToolExecutionContext(
            workspace,
            runId,
            params.path("conversation_id").asString(""),
            params.path("profile_name").asString("gis-build"),
            profileOverrides(params.path("permission_overrides")),
            parseAction(params.path("default_permission").asString("allow")),
            cancellation,
            archiveEvents(workspace, runId),
            uiPort(params.path("_connection_id").asString("")));
    ToolResult result =
        runtime.execute(
            new ToolCall(
                params.path("call_id").asString(""), params.path("name").asString(), arguments),
            context);
    return legacyResult(result);
  }

  private Object loadFile(JsonNode params) {
    Path path = safePath(params);
    if (!Files.isRegularFile(path)) {
      throw invalidParams("path is not a file inside workspace");
    }
    try {
      return Map.of(
          "status", "ok",
          "path", path.toString(),
          "content", Files.readString(path),
          "size", Files.size(path));
    } catch (java.io.IOException exception) {
      throw new RpcException(
          JsonRpcErrorCodes.INTERNAL_ERROR, "Cannot load file", Map.of("path", path.toString()));
    }
  }

  private Object fileInfo(JsonNode params) {
    Path path = safePath(params);
    try {
      return Map.of(
          "status", "ok",
          "path", path.toString(),
          "exists", Files.exists(path),
          "is_file", Files.isRegularFile(path),
          "is_dir", Files.isDirectory(path),
          "size", Files.isRegularFile(path) ? Files.size(path) : 0L);
    } catch (java.io.IOException exception) {
      throw new RpcException(JsonRpcErrorCodes.INTERNAL_ERROR, "Cannot inspect file", null);
    }
  }

  private ObjectNode legacyDefinition(ToolDefinition definition) {
    ObjectNode value = objectMapper.createObjectNode();
    value.put("name", definition.name());
    value.put("display_name", definition.displayName());
    value.put("description", definition.description());
    value.put("category", definition.category());
    value.put("group", definition.group());
    value.put("returns", "structured ToolResult");
    value.put("version", definition.version());
    value.put("risk", definition.risk().name().toLowerCase(java.util.Locale.ROOT));
    value.putArray("examples");
    ArrayNode tags = value.putArray("tags");
    definition.tags().forEach(tags::add);
    ArrayNode params = value.putArray("params");
    Set<String> required = new HashSet<>();
    definition
        .inputSchema()
        .path("required")
        .valueStream()
        .forEach(node -> required.add(node.asString()));
    definition
        .inputSchema()
        .path("properties")
        .properties()
        .forEach(
            entry -> {
              ObjectNode parameter = params.addObject();
              parameter.put("name", entry.getKey());
              parameter.put("type", entry.getValue().path("type").asString("any"));
              parameter.put("description", entry.getValue().path("description").asString(""));
              parameter.put("required", required.contains(entry.getKey()));
              if (entry.getValue().has("default")) {
                parameter.set("default", entry.getValue().path("default"));
              }
              if (entry.getValue().has("enum")) {
                parameter.set("options", entry.getValue().path("enum"));
              }
            });
    value.set("input_schema", definition.inputSchema());
    return value;
  }

  private ObjectNode legacyResult(ToolResult result) {
    ObjectNode value = objectMapper.createObjectNode();
    value.put("success", result.success());
    value.put("status", result.status().name().toLowerCase(java.util.Locale.ROOT));
    value.put("title", result.title());
    value.put("truncated", result.truncated());
    value.set("data", result.output() == null ? objectMapper.nullNode() : result.output());
    value.set("metadata", objectMapper.valueToTree(result.metadata()));
    value.set("artifacts", objectMapper.valueToTree(result.artifacts()));
    if (result.error() == null) {
      value.putNull("error");
      value.putNull("error_detail");
    } else {
      value.put("error", result.error().message());
      value.set("error_detail", objectMapper.valueToTree(result.error()));
    }
    if (result.output() != null && result.output().has("geojson")) {
      value.set("geojson", result.output().path("geojson"));
    } else {
      value.putNull("geojson");
    }
    value.putNull("chart_config");
    return value;
  }

  private ToolEventSink archiveEvents(Path workspace, String runId) {
    if ("direct".equals(runId)) {
      return ToolEventSink.noop();
    }
    var archive = RunArchive.load(workspace, runId);
    if (archive.isEmpty()) {
      return ToolEventSink.noop();
    }
    return event -> archive.get().appendEvent((ObjectNode) objectMapper.valueToTree(event));
  }

  private UiRpcPort uiPort(String connectionId) {
    if (connectionId.isBlank()) {
      return UiRpcPort.disconnected();
    }
    return new UiRpcPort() {
      @Override
      public java.util.concurrent.CompletionStage<JsonNode> request(
          String method, JsonNode params, Duration timeout) {
        return uiGateway.request(connectionId, method, params);
      }

      @Override
      public void notify(String method, JsonNode params) {
        Map<String, Object> values =
            objectMapper.convertValue(params, JsonTypeReferences.STRING_OBJECT_MAP);
        uiGateway.notify(connectionId, method, values);
      }
    };
  }

  private Map<String, PermissionAction> profileOverrides(JsonNode values) {
    Map<String, PermissionAction> overrides = new HashMap<>();
    if (values.isObject()) {
      values
          .properties()
          .forEach(
              entry -> overrides.put(entry.getKey(), parseAction(entry.getValue().asString())));
    }
    return overrides;
  }

  private PermissionAction parseAction(String value) {
    try {
      return PermissionAction.parse(value);
    } catch (IllegalArgumentException exception) {
      throw invalidParams("permission must be allow|ask|deny");
    }
  }

  private Path safePath(JsonNode params) {
    Path workspace = workspace(params);
    if (!params.path("path").isString()) {
      throw invalidParams("path is required");
    }
    Path raw = Path.of(params.path("path").asString());
    Path path = (raw.isAbsolute() ? raw : workspace.resolve(raw)).toAbsolutePath().normalize();
    if (!path.startsWith(workspace)) {
      throw invalidParams("path must remain inside workspace");
    }
    return path;
  }

  private Path workspace(JsonNode params) {
    String raw = params.path("workspace_path").asString(params.path("workspace").asString(""));
    if (raw.isBlank()) {
      throw invalidParams("workspace_path is required");
    }
    return Path.of(raw).toAbsolutePath().normalize();
  }

  private static RpcException invalidParams(String message) {
    return new RpcException(JsonRpcErrorCodes.INVALID_PARAMS, message, null);
  }
}
