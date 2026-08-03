package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.opengis.common.protocol.JsonRpcErrorCodes;
import org.opengis.server.phase8.Phase8ExecutionBridge;
import org.opengis.server.phase8.Phase8Services;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Phase 8 Java implementations of legacy Operation, Script and Worker RPC methods. */
@Component
public final class Phase8RpcMethods {
  private final RpcMethodRegistry methods;
  private final Phase8Services services;
  private final Phase8ExecutionBridge bridge;
  private final UiRpcGateway ui;
  private final ObjectMapper mapper;

  public Phase8RpcMethods(
      RpcMethodRegistry methods,
      Phase8Services services,
      Phase8ExecutionBridge bridge,
      UiRpcGateway ui,
      ObjectMapper mapper) {
    this.methods = methods;
    this.services = services;
    this.bridge = bridge;
    this.ui = ui;
    this.mapper = mapper;
  }

  @PostConstruct
  void register() {
    methods.registerOrReplace("rpc.code.run_script", this::runScript);
    methods.registerOrReplace("rpc.code.cancel_script", this::cancelScript);
    methods.registerOrReplace("rpc.code.list_scripts", this::listScripts);
    methods.registerOrReplace("rpc.code.read_script", this::readScript);
    methods.registerOrReplace("rpc.operations.list", this::listOperations);
    methods.registerOrReplace("rpc.operations.get", this::getOperation);
    methods.registerOrReplace("rpc.operations.run", this::runOperation);
    methods.registerOrReplace("rpc.operations.copy", this::copyOperation);
    methods.registerOrReplace("rpc.operations.create", this::createOperation);
    methods.registerOrReplace("rpc.operations.edit", this::editOperation);
    methods.registerOrReplace("rpc.operations.validate", this::validateOperation);
    methods.registerOrReplace("rpc.operations.promote", this::promoteOperation);
    methods.registerOrReplace("rpc.operations.legacy_report", this::legacyOperationReport);
    methods.registerOrReplace("rpc.worker.list", this::listWorkers);
    methods.registerOrReplace("rpc.worker.get", this::getWorker);
    methods.registerOrReplace("rpc.worker.start", this::startWorker);
    methods.registerOrReplace("rpc.worker.pause", this::pauseWorker);
    methods.registerOrReplace("rpc.worker.restart", this::restartWorker);
    methods.registerOrReplace("rpc.worker.delete", this::deleteWorker);
    methods.registerOrReplace("rpc.worker.wait", this::waitWorker);
    methods.registerOrReplace("rpc.worker.migration.inspect", this::migrateWorker);
  }

  private Object runScript(JsonNode params) {
    return services.runScript(context(params), params);
  }

  private Object cancelScript(JsonNode params) {
    return services.cancelScript(params.path("run_id").asText(""));
  }

  private Object listScripts(JsonNode params) {
    return services.listScripts(
        workspace(params), params.path("query").asText(""), params.path("limit").asInt(50));
  }

  private Object readScript(JsonNode params) {
    return services.readScript(
        workspace(params), required(params, "path"), params.path("max_characters").asInt(100_000));
  }

  private Object listOperations(JsonNode params) {
    return services
        .operations()
        .list(workspace(params), params.path("query").asText(""), params.path("limit").asInt(50));
  }

  private Object getOperation(JsonNode params) {
    return services
        .operations()
        .get(
            workspace(params),
            required(params, "operation_id"),
            params.path("include_code").asBoolean(false),
            params.path("max_code_chars").asInt(40_000));
  }

  private Object runOperation(JsonNode params) {
    ToolExecutionContext context = context(params);
    return services
        .operations()
        .run(
            context.workspace(),
            required(params, "operation_id"),
            params.path("params").isObject() ? params.path("params") : mapper.createObjectNode(),
            Duration.ofSeconds(params.path("timeout_seconds").asLong(600)),
            params.path("offline").asBoolean(true),
            context.cancellation(),
            bridge.callbacks(context));
  }

  private Object copyOperation(JsonNode params) {
    return services
        .operations()
        .copyToWorkspace(
            workspace(params),
            required(params, "operation_id"),
            params.path("overwrite").asBoolean(false));
  }

  private Object createOperation(JsonNode params) {
    return services
        .operations()
        .create(workspace(params), params, params.path("overwrite").asBoolean(false));
  }

  private Object editOperation(JsonNode params) {
    return services.operations().edit(workspace(params), required(params, "operation_id"), params);
  }

  private Object validateOperation(JsonNode params) {
    return services
        .operations()
        .validate(
            workspace(params),
            required(params, "operation_id"),
            params.path("params").isObject() ? params.path("params") : mapper.createObjectNode(),
            params.path("offline").asBoolean(true));
  }

  private Object promoteOperation(JsonNode params) {
    return services
        .operations()
        .promoteScript(
            workspace(params),
            required(params, "script_path"),
            params.path("operation_id").asText(""),
            params.path("overwrite").asBoolean(false));
  }

  private Object legacyOperationReport(JsonNode params) {
    return services.operations().legacyReport(workspace(params), required(params, "operation_id"));
  }

  private Object listWorkers(JsonNode params) {
    return services.workers().list(workspace(params), params.path("include_logs").asBoolean(true));
  }

  private Object getWorker(JsonNode params) {
    return Map.of(
        "worker",
        services
            .workers()
            .get(
                workspace(params),
                required(params, "worker_id"),
                params.path("include_logs").asBoolean(true)));
  }

  private Object startWorker(JsonNode params) {
    ToolExecutionContext context = context(params);
    return Map.of(
        "worker",
        services
            .workers()
            .createAndStart(
                context.workspace(),
                params,
                bridge.callbacks(context),
                services.workerEvents(context)));
  }

  private Object pauseWorker(JsonNode params) {
    return Map.of(
        "worker",
        services
            .workers()
            .pause(
                workspace(params),
                required(params, "worker_id"),
                params.path("reason").asText("ui_pause")));
  }

  private Object restartWorker(JsonNode params) {
    ToolExecutionContext context = context(params);
    return Map.of(
        "worker",
        services
            .workers()
            .restart(
                context.workspace(),
                required(params, "worker_id"),
                bridge.callbacks(context),
                services.workerEvents(context)));
  }

  private Object deleteWorker(JsonNode params) {
    return Map.of(
        "worker", services.workers().delete(workspace(params), required(params, "worker_id")));
  }

  private Object waitWorker(JsonNode params) {
    return Map.of(
        "worker",
        services
            .workers()
            .waitForUpdate(
                workspace(params),
                required(params, "worker_id"),
                params.path("after_version").asLong(0),
                Duration.ofSeconds(Math.min(60, params.path("timeout").asLong(20)))));
  }

  private Object migrateWorker(JsonNode params) {
    return services.migrateWorkers(workspace(params), params.path("worker_id").asText(""));
  }

  private ToolExecutionContext context(JsonNode params) {
    return new ToolExecutionContext(
        workspace(params),
        params.path("run_id").asText("direct"),
        params.path("conversation_id").asText(""),
        params.path("profile_name").asText("gis-build"),
        Map.of(),
        PermissionAction.ALLOW,
        new CancellationToken(),
        ToolEventSink.noop(),
        uiPort(params.path("_connection_id").asText("")));
  }

  private UiRpcPort uiPort(String connectionId) {
    if (connectionId.isBlank()) return UiRpcPort.disconnected();
    return new UiRpcPort() {
      @Override
      public java.util.concurrent.CompletionStage<JsonNode> request(
          String method, JsonNode params, Duration timeout) {
        return ui.request(connectionId, method, params);
      }

      @Override
      public void notify(String method, JsonNode params) {
        ui.notify(connectionId, method, mapper.convertValue(params, Map.class));
      }
    };
  }

  private static Path workspace(JsonNode params) {
    String value = params.path("workspace_path").asText(params.path("workspace").asText(""));
    if (value.isBlank()) throw invalid("workspace_path is required");
    return Path.of(value).toAbsolutePath().normalize();
  }

  private static String required(JsonNode params, String name) {
    String value = params.path(name).asText();
    if (value.isBlank()) throw invalid(name + " is required");
    return value;
  }

  private static RpcException invalid(String message) {
    return new RpcException(JsonRpcErrorCodes.INVALID_PARAMS, message, null);
  }
}
