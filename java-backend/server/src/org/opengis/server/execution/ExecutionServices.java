/** 文件职责：server 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.server.execution;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opengis.automation.code.archive.JavaCodeArchive;
import org.opengis.automation.code.dependency.DependencyResolver;
import org.opengis.automation.code.runner.JavaCodeRunner;
import org.opengis.automation.code.runner.ScriptRunRequest;
import org.opengis.gis.operation.OperationService;
import org.opengis.core.persistence.JsonTypeReferences;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.automation.worker.WorkerEventSink;
import org.opengis.automation.worker.WorkerManager;
import org.opengis.automation.worker.WorkerMigrationService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Application facade shared by execution tools, RPC methods and workflow adapters. */
@Component
public final class ExecutionServices {
  private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
  private static final Pattern PUBLIC_CLASS =
      Pattern.compile("\\bpublic\\s+(?:final\\s+)?class\\s+(\\w+)");
  private final ObjectMapper mapper;
  private final ScriptExecutionBridge bridge;
  private final OperationService operations;
  private final JavaCodeRunner scripts;
  private final WorkerManager workers;
  private final WorkerMigrationService workerMigration;
  private final Map<String, CancellationToken> activeScripts = new ConcurrentHashMap<>();

  public ExecutionServices(ObjectMapper mapper, ScriptExecutionBridge bridge) {
    this.mapper = mapper;
    this.bridge = bridge;
    this.operations = new OperationService(mapper);
    this.scripts = new JavaCodeRunner(mapper);
    this.workers = new WorkerManager(mapper);
    this.workerMigration = new WorkerMigrationService(mapper);
  }

  public OperationService operations() {
    return operations;
  }

  public WorkerManager workers() {
    return workers;
  }

  public ObjectNode runScript(ToolExecutionContext context, JsonNode arguments) {
    String source = arguments.path("code").asString();
    if (source.isBlank()) throw new IllegalArgumentException("code is required");
    String runId = arguments.path("run_id").asString(context.runId());
    if (runId.isBlank() || "direct".equals(runId)) runId = "script-" + UUID.randomUUID();
    String entryClass = arguments.path("entry_class").asString();
    if (entryClass.isBlank()) entryClass = inferEntryClass(source);
    CancellationToken cancellation = context.cancellation();
    if (activeScripts.putIfAbsent(runId, cancellation) != null) {
      throw new IllegalStateException("Script run is already active: " + runId);
    }
    Instant started = Instant.now();
    ToolExecutionContext runContext =
        new ToolExecutionContext(
            context.workspace(),
            runId,
            context.conversationId(),
            context.profileName(),
            context.profileOverrides(),
            context.defaultPermission(),
            cancellation,
            context.eventSink(),
            context.uiRpc());
    try {
      Map<String, Object> params =
          arguments.path("params").isObject()
              ? mapper.convertValue(arguments.path("params"), JsonTypeReferences.STRING_OBJECT_MAP)
              : Map.of();
      var result =
          scripts.run(
              new ScriptRunRequest(
                  context.workspace(),
                  runId,
                  arguments.path("name").asString("java-script"),
                  entryClass,
                  source,
                  params,
                  strings(arguments.path("permissions")),
                  dependencyRequests(arguments.path("dependencies")),
                  arguments.path("offline").asBoolean(true),
                  Duration.ofSeconds(clamp(arguments.path("exec_timeout").asLong(600), 1, 3600)),
                  (int) clamp(arguments.path("max_heap_mb").asLong(256), 64, 2048),
                  cancellation),
              bridge.callbacks(runContext));
      ObjectNode response = mapper.valueToTree(result);
      response.put("run_id", result.runId());
      response.put("ok", "completed".equals(result.status()));
      response.put("duration_ms", Duration.between(started, Instant.now()).toMillis());
      response.put("stdout_path", result.stdoutPath().toString());
      response.put("stderr_path", result.stderrPath().toString());
      response.putPOJO(
          "logs", List.of(result.stdoutPath().toString(), result.stderrPath().toString()));
      bridge
          .callbacks(runContext)
          .event(
              result.status(), mapper.convertValue(response, JsonTypeReferences.STRING_OBJECT_MAP));
      return response;
    } finally {
      activeScripts.remove(runId, cancellation);
    }
  }

  public ObjectNode cancelScript(String runId) {
    int cancelled = 0;
    if (runId != null && !runId.isBlank()) {
      CancellationToken token = activeScripts.get(runId);
      if (token != null) {
        token.cancel();
        cancelled = 1;
      }
    } else {
      for (CancellationToken token : activeScripts.values()) {
        token.cancel();
        cancelled++;
      }
    }
    return mapper
        .createObjectNode()
        .put("status", cancelled > 0 ? "cancelling" : "idle")
        .put("cancelled", cancelled);
  }

  public ObjectNode listScripts(Path workspace, String query, int limit) {
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.set("scripts", mapper.valueToTree(new JavaCodeArchive(workspace).list(query, limit)));
    return result;
  }

  public ObjectNode readScript(Path workspace, String path, int maximumCharacters) {
    return new JavaCodeArchive(workspace).read(path, maximumCharacters);
  }

  public ObjectNode migrateWorkers(Path workspace, String workerId) {
    return workerId == null || workerId.isBlank()
        ? workerMigration.inspectAll(workspace)
        : workerMigration.inspect(workspace, workerId);
  }

  public WorkerEventSink workerEvents(ToolExecutionContext context) {
    return (method, parameters) -> {
      try {
        context.uiRpc().notify(method, mapper.valueToTree(parameters));
      } catch (RuntimeException ignored) {
        // Worker metadata/logs remain queryable when no Renderer connection is attached.
      }
    };
  }

  private static String inferEntryClass(String source) {
    Matcher type = PUBLIC_CLASS.matcher(source);
    if (!type.find()) {
      throw new IllegalArgumentException("entry_class is required when no public class is present");
    }
    Matcher packageName = PACKAGE.matcher(source);
    return (packageName.find() ? packageName.group(1) + "." : "") + type.group(1);
  }

  private static Set<String> strings(JsonNode values) {
    Set<String> result = new HashSet<>();
    if (values.isArray()) values.forEach(value -> result.add(value.asString()));
    if (result.isEmpty()) result.add("workspace_files");
    return Set.copyOf(result);
  }

  private static List<DependencyResolver.Request> dependencyRequests(JsonNode values) {
    List<DependencyResolver.Request> result = new ArrayList<>();
    if (!values.isArray()) return result;
    for (JsonNode value : values) {
      if (value.isString()) result.add(new DependencyResolver.Request(value.asString(), false, ""));
      else
        result.add(
            new DependencyResolver.Request(
                value.path("coordinate").asString(),
                value.path("approved").asBoolean(false),
                value.path("checksum").asString("")));
    }
    return result;
  }

  private static long clamp(long value, long minimum, long maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  @PreDestroy
  void close() {
    activeScripts.values().forEach(CancellationToken::cancel);
    workers.close();
  }
}
