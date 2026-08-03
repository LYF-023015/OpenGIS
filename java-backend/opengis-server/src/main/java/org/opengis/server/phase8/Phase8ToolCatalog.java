package org.opengis.server.phase8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Python-compatible Phase 8 tool names backed entirely by Java services. */
public final class Phase8ToolCatalog {
  private Phase8ToolCatalog() {}

  public static List<OpenGisTool> create(
      ObjectMapper mapper, Phase8Services services, Phase8ExecutionBridge bridge) {
    List<OpenGisTool> values = new ArrayList<>();
    values.add(
        tool(
            mapper,
            "list_operations",
            "List Reusable Operations",
            "List Java v2, built-in and legacy read-only Operations.",
            "operation",
            ToolRisk.READ,
            List.of(),
            (a, c) ->
                services
                    .operations()
                    .list(c.workspace(), a.path("query").asText(""), integer(a, "limit", 50))));
    values.add(
        tool(
            mapper,
            "get_operation",
            "Get Reusable Operation",
            "Read an Operation manifest and optional Java source.",
            "operation",
            ToolRisk.READ,
            List.of("operation_id"),
            (a, c) ->
                services
                    .operations()
                    .get(
                        c.workspace(),
                        required(a, "operation_id"),
                        a.path("include_code").asBoolean(false),
                        integer(a, "max_code_chars", 40_000))));
    values.add(
        tool(
            mapper,
            "copy_operation_to_workspace",
            "Copy Operation To Workspace",
            "Copy a built-in or legacy Operation into an editable Java v2 workspace package.",
            "operation",
            ToolRisk.WRITE,
            List.of("operation_id"),
            (a, c) ->
                services
                    .operations()
                    .copyToWorkspace(
                        c.workspace(),
                        required(a, "operation_id"),
                        a.path("overwrite").asBoolean(false))));
    values.add(
        tool(
            mapper,
            "validate_operation",
            "Validate Reusable Operation",
            "Validate manifest, schema, Maven dependencies and Java source compilation.",
            "operation",
            ToolRisk.PROCESS,
            List.of("operation_id"),
            (a, c) ->
                services
                    .operations()
                    .validate(
                        c.workspace(),
                        required(a, "operation_id"),
                        object(a, "params", mapper),
                        a.path("offline").asBoolean(true))));
    values.add(
        tool(
            mapper,
            "run_operation",
            "Run Reusable Operation",
            "Run a trusted built-in or isolated child-JVM Java Operation at a fixed revision.",
            "operation",
            ToolRisk.PROCESS,
            List.of("operation_id", "params"),
            (a, c) ->
                services
                    .operations()
                    .run(
                        c.workspace(),
                        required(a, "operation_id"),
                        object(a, "params", mapper),
                        Duration.ofSeconds(integer(a, "timeout_seconds", 600)),
                        a.path("offline").asBoolean(true),
                        c.cancellation(),
                        bridge.callbacks(c))));
    values.add(
        tool(
            mapper,
            "create_operation",
            "Create Reusable Operation",
            "Create a standard Maven-layout Java Operation v2 package.",
            "operation",
            ToolRisk.WRITE,
            List.of("operation_id", "name", "code"),
            (a, c) ->
                services
                    .operations()
                    .create(c.workspace(), a, a.path("overwrite").asBoolean(false))));
    values.add(
        tool(
            mapper,
            "edit_operation",
            "Edit Reusable Operation",
            "Edit a workspace Operation and create an immutable revision snapshot.",
            "operation",
            ToolRisk.WRITE,
            List.of("operation_id"),
            (a, c) -> services.operations().edit(c.workspace(), required(a, "operation_id"), a)));
    values.add(
        tool(
            mapper,
            "promote_script_to_operation",
            "Promote Script To Operation",
            "Promote only a successfully executed archived Java script.",
            "operation",
            ToolRisk.WRITE,
            List.of("script_path"),
            (a, c) ->
                services
                    .operations()
                    .promoteScript(
                        c.workspace(),
                        required(a, "script_path"),
                        a.path("operation_id").asText(""),
                        a.path("overwrite").asBoolean(false))));
    values.add(
        tool(
            mapper,
            "execute_code",
            "Execute Java Code",
            "Compile and run OpenGisScript source in an isolated, cancellable child JVM.",
            "code",
            ToolRisk.PROCESS,
            List.of("code"),
            (a, c) -> services.runScript(c, a)));
    values.add(
        tool(
            mapper,
            "start_worker",
            "Start Java Worker",
            "Create and start a persistent OpenGisWorker service package.",
            "worker",
            ToolRisk.PROCESS,
            List.of("name", "code"),
            (a, c) ->
                services
                    .workers()
                    .createAndStart(
                        c.workspace(), a, bridge.callbacks(c), services.workerEvents(c))));
    values.add(
        tool(
            mapper,
            "start_dynamic_map_worker",
            "Start Dynamic Map Worker",
            "Create a Java Worker that publishes ordered full/diff dynamic-map frames.",
            "worker",
            ToolRisk.PROCESS,
            List.of("name", "code"),
            (a, c) ->
                services
                    .workers()
                    .createAndStart(
                        c.workspace(), a, bridge.callbacks(c), services.workerEvents(c))));
    values.add(workerReadTool(mapper, services, "get_worker", true));
    values.add(workerReadTool(mapper, services, "list_workers", false));
    values.add(
        tool(
            mapper,
            "wait_worker_update",
            "Wait For Worker Update",
            "Wait for a Worker state_version change without polling loops.",
            "worker",
            ToolRisk.READ,
            List.of("worker_id"),
            (a, c) ->
                services
                    .workers()
                    .waitForUpdate(
                        c.workspace(),
                        required(a, "worker_id"),
                        a.path("after_version").asLong(0),
                        Duration.ofSeconds(Math.min(60, integer(a, "timeout", 20))))));
    values.add(
        tool(
            mapper,
            "restart_worker",
            "Restart Java Worker",
            "Restart an existing Java Worker with the same package and id.",
            "worker",
            ToolRisk.PROCESS,
            List.of("worker_id"),
            (a, c) ->
                services
                    .workers()
                    .restart(
                        c.workspace(),
                        required(a, "worker_id"),
                        bridge.callbacks(c),
                        services.workerEvents(c))));
    values.add(
        tool(
            mapper,
            "pause_worker",
            "Pause Java Worker",
            "Stop a Worker process while preserving its package and metadata.",
            "worker",
            ToolRisk.PROCESS,
            List.of("worker_id"),
            (a, c) ->
                services
                    .workers()
                    .pause(
                        c.workspace(),
                        required(a, "worker_id"),
                        a.path("reason").asText("pause"))));
    values.add(
        tool(
            mapper,
            "delete_worker",
            "Delete Java Worker",
            "Stop and remove one exact workspace Worker package.",
            "worker",
            ToolRisk.DESTRUCTIVE,
            List.of("worker_id"),
            (a, c) -> services.workers().delete(c.workspace(), required(a, "worker_id"))));
    return List.copyOf(values);
  }

  private static OpenGisTool workerReadTool(
      ObjectMapper mapper, Phase8Services services, String name, boolean single) {
    return tool(
        mapper,
        name,
        single ? "Get Java Worker" : "List Java Workers",
        single ? "Inspect Worker state, resources and logs." : "List workspace Java Workers.",
        "worker",
        ToolRisk.READ,
        single ? List.of("worker_id") : List.of(),
        (a, c) ->
            single
                ? services
                    .workers()
                    .get(
                        c.workspace(),
                        required(a, "worker_id"),
                        a.path("include_logs").asBoolean(true))
                : services.workers().list(c.workspace(), a.path("include_logs").asBoolean(false)));
  }

  private static OpenGisTool tool(
      ObjectMapper mapper,
      String name,
      String displayName,
      String description,
      String group,
      ToolRisk risk,
      List<String> required,
      BiFunction<JsonNode, ToolExecutionContext, JsonNode> function) {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    parameterNames(name).forEach(parameter -> properties.putObject(parameter));
    var requiredNode = schema.putArray("required");
    required.forEach(requiredNode::add);
    schema.put("additionalProperties", true);
    ToolDefinition definition =
        new ToolDefinition(
            name,
            displayName,
            description,
            "system",
            group,
            "2.0",
            risk,
            schema,
            List.of("java", "phase8", group));
    return new OpenGisTool() {
      @Override
      public ToolDefinition definition() {
        return definition;
      }

      @Override
      public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
        return function.apply(arguments, context);
      }
    };
  }

  private static List<String> parameterNames(String name) {
    return switch (name) {
      case "list_operations" -> List.of("query", "limit");
      case "get_operation" -> List.of("operation_id", "include_code", "max_code_chars");
      case "copy_operation_to_workspace" -> List.of("operation_id", "overwrite");
      case "validate_operation" -> List.of("operation_id", "params", "offline");
      case "run_operation" -> List.of("operation_id", "params", "timeout_seconds", "offline");
      case "create_operation" ->
          List.of(
              "operation_id",
              "name",
              "description",
              "entry_class",
              "code",
              "input_schema",
              "output_schema",
              "dependencies",
              "permissions",
              "overwrite");
      case "edit_operation" ->
          List.of(
              "operation_id",
              "name",
              "description",
              "code",
              "input_schema",
              "output_schema",
              "dependencies",
              "permissions",
              "readme",
              "status");
      case "promote_script_to_operation" -> List.of("script_path", "operation_id", "overwrite");
      case "execute_code" ->
          List.of(
              "code",
              "entry_class",
              "name",
              "params",
              "permissions",
              "dependencies",
              "offline",
              "exec_timeout",
              "max_heap_mb");
      case "start_worker", "start_dynamic_map_worker" ->
          List.of(
              "name",
              "description",
              "entry_class",
              "code",
              "config",
              "permissions",
              "dependencies",
              "auto_restart");
      case "get_worker" -> List.of("worker_id", "include_logs");
      case "list_workers" -> List.of("include_logs");
      case "wait_worker_update" -> List.of("worker_id", "after_version", "timeout");
      case "restart_worker" -> List.of("worker_id");
      case "pause_worker" -> List.of("worker_id", "reason");
      case "delete_worker" -> List.of("worker_id");
      default -> List.of();
    };
  }

  private static String required(JsonNode value, String key) {
    String result = value.path(key).asText();
    if (result.isBlank()) throw new IllegalArgumentException(key + " is required");
    return result;
  }

  private static int integer(JsonNode value, String key, int fallback) {
    return value.has(key) ? value.path(key).asInt(fallback) : fallback;
  }

  private static JsonNode object(JsonNode value, String key, ObjectMapper mapper) {
    return value.path(key).isObject() ? value.path(key) : mapper.createObjectNode();
  }
}
