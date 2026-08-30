package org.opengis.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.opengis.code.dependency.DependencyResolver;
import org.opengis.code.runner.JavaScriptRunner;
import org.opengis.code.runner.ScriptCallbacks;
import org.opengis.code.runner.ScriptRunRequest;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.JsonTypeReferences;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Workspace Java Worker package manager with bounded concurrency and finite restart backoff. */
public final class WorkerManager implements AutoCloseable {
  public static final int DEFAULT_MAX_RUNNING = 2;
  private static final int MAX_RESTARTS = 3;
  private final ObjectMapper mapper;
  private final JsonFileStore files;
  private final JavaScriptRunner runner;
  private final int maximumRunning;
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
  private final ConcurrentHashMap<String, ActiveWorker> active = new ConcurrentHashMap<>();
  private final Object stateMonitor = new Object();

  public WorkerManager(ObjectMapper mapper) {
    this(mapper, DEFAULT_MAX_RUNNING);
  }

  public WorkerManager(ObjectMapper mapper, int maximumRunning) {
    this.mapper = mapper;
    this.files = new JsonFileStore(mapper);
    this.runner = new JavaScriptRunner(mapper);
    this.maximumRunning = Math.max(1, maximumRunning);
  }

  public ObjectNode createAndStart(
      Path workspace,
      JsonNode specification,
      ScriptCallbacks toolCallbacks,
      WorkerEventSink events) {
    Path root = workerRoot(workspace);
    String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    String name = text(specification, "name", "Java Worker");
    String folderName = slug(name) + "-" + workerId;
    Path folder = root.resolve(folderName);
    String entryClass = text(specification, "entry_class", "workspace.workers." + javaClass(name));
    String source = text(specification, "code", template(entryClass));
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("schema_version", "2.0");
    manifest.put("api_version", "1.0");
    manifest.put("worker_id", workerId);
    manifest.put("name", name);
    manifest.put(
        "description", text(specification, "description", "Workspace Java resident worker"));
    manifest.put("runtime", "java");
    manifest.put("entry_class", entryClass);
    manifest.put("entry", "src/main/java/" + entryClass.replace('.', '/') + ".java");
    manifest.set(
        "permissions",
        specification.path("permissions").isArray()
            ? specification.path("permissions").deepCopy()
            : mapper.valueToTree(List.of("workspace_files")));
    manifest.set(
        "dependencies",
        specification.path("dependencies").isArray()
            ? specification.path("dependencies").deepCopy()
            : mapper.createArrayNode());
    manifest.put("auto_restart", specification.path("auto_restart").asBoolean(false));
    manifest.put("created_at", Instant.now().toString());
    files.write(folder.resolve("manifest.json"), manifest);
    files.write(
        folder.resolve("config.json"),
        specification.path("config").isObject()
            ? specification.path("config")
            : mapper.createObjectNode());
    files.writeText(
        folder.resolve(manifest.path("entry").asString()), source.stripTrailing() + "\n");
    files.writeText(
        folder.resolve("README.md"),
        "# "
            + name
            + "\n\nJava Worker package. Keep datasource, service and publisher concerns separate as the package grows.\n");
    ObjectNode metadata = initialMetadata(workspace, folder, manifest);
    save(folder, metadata);
    return start(workspace, workerId, toolCallbacks, events);
  }

  public ObjectNode start(
      Path workspace, String workerId, ScriptCallbacks toolCallbacks, WorkerEventSink events) {
    Path folder = findFolder(workspace, workerId);
    String key = key(workspace, workerId);
    ActiveWorker prior = active.get(key);
    if (prior != null && prior.isRunning()) return snapshot(prior);
    long running = active.values().stream().filter(ActiveWorker::isRunning).count();
    if (running >= maximumRunning)
      throw new IllegalStateException("Maximum running workers reached: " + maximumRunning);
    ObjectNode manifest = files.readObject(folder.resolve("manifest.json"));
    if (!"java".equalsIgnoreCase(manifest.path("runtime").asString())) {
      throw new IllegalArgumentException("Legacy Python Worker cannot run in Java mode");
    }
    String source = files.readText(folder.resolve(manifest.path("entry").asString()));
    ObjectNode metadata = files.readObject(folder.resolve("metadata.json"));
    CancellationToken cancellation = new CancellationToken();
    ActiveWorker worker =
        new ActiveWorker(
            workspace.toAbsolutePath().normalize(),
            folder,
            workerId,
            cancellation,
            toolCallbacks == null ? ScriptCallbacks.disconnected() : toolCallbacks,
            events == null ? WorkerEventSink.noop() : events,
            metadata,
            new ConcurrentHashMap<>());
    active.put(key, worker);
    update(
        worker,
        value -> value.put("status", "starting").put("restored", false).put("last_error", ""));
    worker.future = CompletableFuture.runAsync(() -> execute(worker, manifest, source), executor);
    return snapshot(worker);
  }

  public ObjectNode pause(Path workspace, String workerId, String reason) {
    ActiveWorker worker = active.get(key(workspace, workerId));
    if (worker == null) {
      Path folder = findFolder(workspace, workerId);
      ObjectNode metadata = files.readObject(folder.resolve("metadata.json"));
      metadata.put("status", "paused");
      metadata.put("pause_reason", reason == null ? "pause" : reason);
      metadata.put("state_version", metadata.path("state_version").asLong() + 1);
      save(folder, metadata);
      return metadata;
    }
    worker.userPaused = true;
    worker.cancellation.cancel();
    update(
        worker,
        value ->
            value.put("status", "paused").put("pause_reason", reason == null ? "pause" : reason));
    return snapshot(worker);
  }

  public ObjectNode restart(
      Path workspace, String workerId, ScriptCallbacks callbacks, WorkerEventSink events) {
    ActiveWorker current = active.get(key(workspace, workerId));
    if (current != null) {
      current.userPaused = true;
      current.cancellation.cancel();
      if (current.future != null) {
        try {
          current.future.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          // The child process cancellation path owns final cleanup.
        }
      }
      active.remove(key(workspace, workerId), current);
    }
    return start(workspace, workerId, callbacks, events);
  }

  public ObjectNode delete(Path workspace, String workerId) {
    pause(workspace, workerId, "delete");
    active.remove(key(workspace, workerId));
    Path folder = findFolder(workspace, workerId);
    Path root = workerRoot(workspace).toAbsolutePath().normalize();
    Path target = folder.toAbsolutePath().normalize();
    if (!target.startsWith(root) || target.equals(root))
      throw new IllegalArgumentException("Unsafe Worker delete target");
    deleteTree(target);
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.put("worker_id", workerId);
    result.put("deleted", true);
    return result;
  }

  public ObjectNode get(Path workspace, String workerId, boolean includeLogs) {
    ActiveWorker worker = active.get(key(workspace, workerId));
    ObjectNode value =
        worker == null
            ? files.readObject(findFolder(workspace, workerId).resolve("metadata.json"))
            : snapshot(worker);
    if (includeLogs) {
      value.put("stdout_tail", tail(path(value, "stdout_path"), 100));
      value.put("stderr_tail", tail(path(value, "stderr_path"), 100));
    }
    value.set("resources", mapper.valueToTree(resources(value.path("pid").asLong(-1))));
    return value;
  }

  public ObjectNode list(Path workspace, boolean includeLogs) {
    restore(workspace);
    ObjectNode result = mapper.createObjectNode();
    result.put("success", true);
    result.put("max_running", maximumRunning);
    var workers = result.putArray("workers");
    for (Path folder : folders(workspace)) {
      ObjectNode value = files.readObject(folder.resolve("metadata.json"));
      if (includeLogs) value.put("stdout_tail", tail(path(value, "stdout_path"), 20));
      workers.add(value);
    }
    return result;
  }

  public ObjectNode waitForUpdate(
      Path workspace, String workerId, long afterVersion, Duration timeout) {
    long deadline =
        System.nanoTime() + (timeout == null ? Duration.ofSeconds(30) : timeout).toNanos();
    synchronized (stateMonitor) {
      while (System.nanoTime() < deadline) {
        ObjectNode current = get(workspace, workerId, false);
        if (current.path("state_version").asLong() > afterVersion) return current;
        long millis =
            Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        try {
          stateMonitor.wait(millis);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Worker wait was interrupted", exception);
        }
      }
    }
    return get(workspace, workerId, false);
  }

  public void restore(Path workspace) {
    for (Path folder : folders(workspace)) {
      ObjectNode metadata = files.readObject(folder.resolve("metadata.json"));
      String status = metadata.path("status").asString();
      if ("running".equals(status) || "starting".equals(status) || "restarting".equals(status)) {
        metadata.put("status", "paused");
        metadata.put("restored", true);
        metadata.put("last_error", "worker restored after backend restart; explicitly restart it");
        metadata.put("state_version", metadata.path("state_version").asLong() + 1);
        save(folder, metadata);
      }
    }
  }

  private void execute(ActiveWorker worker, ObjectNode manifest, String source) {
    String runId =
        worker.workerId
            + "-r"
            + (worker.metadata.path("restart_attempts").asInt() + 1)
            + "-"
            + UUID.randomUUID().toString().substring(0, 6);
    update(
        worker,
        value -> {
          value.put("run_id", runId);
          value.put("started_at", Instant.now().toString());
          value.put(
              "stdout_path",
              worker
                  .workspace
                  .relativize(
                      worker
                          .workspace
                          .resolve(".opengis/script-runs")
                          .resolve(runId)
                          .resolve("stdout.log"))
                  .toString()
                  .replace('\\', '/'));
          value.put(
              "stderr_path",
              worker
                  .workspace
                  .relativize(
                      worker
                          .workspace
                          .resolve(".opengis/script-runs")
                          .resolve(runId)
                          .resolve("stderr.log"))
                  .toString()
                  .replace('\\', '/'));
        });
    ObjectNode config = files.readObject(worker.folder.resolve("config.json"));
    Map<String, Object> parameters =
        mapper.convertValue(config, JsonTypeReferences.STRING_OBJECT_MAP);
    ScriptCallbacks callbacks = callbacks(worker);
    try {
      var result =
          runner.run(
              new ScriptRunRequest(
                  worker.workspace,
                  runId,
                  manifest.path("name").asString(worker.workerId),
                  manifest.path("entry_class").asString(),
                  source,
                  parameters,
                  strings(manifest.path("permissions")),
                  dependencyRequests(manifest.path("dependencies")),
                  true,
                  Duration.ofDays(365),
                  256,
                  worker.cancellation),
              callbacks);
      if (worker.userPaused || "cancelled".equals(result.status())) {
        update(
            worker,
            value -> value.put("status", "paused").put("finished_at", Instant.now().toString()));
      } else if ("completed".equals(result.status())) {
        update(
            worker,
            value -> value.put("status", "completed").put("finished_at", Instant.now().toString()));
      } else {
        failed(worker, manifest, source, result.error());
      }
    } catch (RuntimeException exception) {
      if (worker.userPaused || worker.cancellation.isCancelled()) {
        update(
            worker,
            value -> value.put("status", "paused").put("finished_at", Instant.now().toString()));
      } else {
        failed(worker, manifest, source, exception.getMessage());
      }
    }
  }

  private ScriptCallbacks callbacks(ActiveWorker worker) {
    return new ScriptCallbacks() {
      @Override
      public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
        return worker.toolCallbacks.callTool(name, arguments);
      }

      @Override
      public void mapEvent(String method, Map<String, Object> parameters) {
        if (!method.startsWith("rpc.ui.map."))
          throw new IllegalArgumentException("Worker map method is not allowed");
        String layer = String.valueOf(parameters.getOrDefault("layer_id", ""));
        long sequence = number(parameters.get("sequence"));
        Long previous = worker.layerSequences.put(layer, sequence);
        if (previous != null && sequence <= previous)
          throw new IllegalArgumentException("Dynamic layer sequence must increase");
        worker.events.emit(method, parameters);
      }

      @Override
      public void event(String type, Map<String, Object> payload) {
        if ("started".equals(type)) {
          update(
              worker,
              value -> value.put("status", "running").put("pid", number(payload.get("pid"))));
        }
        worker.events.emit(
            "rpc.worker.event",
            Map.of("worker_id", worker.workerId, "type", type, "payload", payload));
      }
    };
  }

  private void failed(ActiveWorker worker, ObjectNode manifest, String source, String error) {
    int attempts = worker.metadata.path("restart_attempts").asInt();
    boolean autoRestart = manifest.path("auto_restart").asBoolean(false);
    update(
        worker,
        value ->
            value
                .put("status", "failed")
                .put("last_error", error == null ? "Worker failed" : error)
                .put("finished_at", Instant.now().toString()));
    if (autoRestart && attempts < MAX_RESTARTS && !worker.userPaused) {
      int next = attempts + 1;
      long delay = Math.min(30, 1L << attempts);
      update(
          worker,
          value ->
              value
                  .put("status", "restarting")
                  .put("restart_attempts", next)
                  .put("next_restart_seconds", delay));
      executor.schedule(() -> execute(worker, manifest, source), delay, TimeUnit.SECONDS);
    }
  }

  private void update(ActiveWorker worker, java.util.function.Consumer<ObjectNode> change) {
    synchronized (worker) {
      change.accept(worker.metadata);
      worker.metadata.put("state_version", worker.metadata.path("state_version").asLong() + 1);
      worker.metadata.put("updated_at", Instant.now().toString());
      save(worker.folder, worker.metadata);
    }
    synchronized (stateMonitor) {
      stateMonitor.notifyAll();
    }
  }

  private ObjectNode initialMetadata(Path workspace, Path folder, ObjectNode manifest) {
    ObjectNode value = mapper.createObjectNode();
    value.put("schema_version", "2.0");
    value.put("worker_id", manifest.path("worker_id").asString());
    value.put("name", manifest.path("name").asString());
    value.put("description", manifest.path("description").asString());
    value.put("runtime", manifest.path("runtime").asString());
    value.put("entry_class", manifest.path("entry_class").asString());
    value.put("workspace_path", workspace.toAbsolutePath().normalize().toString());
    value.put(
        "folder",
        workspace.toAbsolutePath().normalize().relativize(folder).toString().replace('\\', '/'));
    value.put("status", "created");
    value.put("state_version", 1);
    value.put("restart_attempts", 0);
    value.put("pid", -1);
    value.put("restored", false);
    value.put("created_at", Instant.now().toString());
    value.put("updated_at", Instant.now().toString());
    return value;
  }

  private ObjectNode snapshot(ActiveWorker worker) {
    synchronized (worker) {
      return worker.metadata.deepCopy();
    }
  }

  private void save(Path folder, ObjectNode metadata) {
    files.write(folder.resolve("metadata.json"), metadata);
  }

  private Path findFolder(Path workspace, String workerId) {
    return folders(workspace).stream()
        .filter(
            folder ->
                files
                    .readObject(folder.resolve("metadata.json"))
                    .path("worker_id")
                    .asString()
                    .equals(workerId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
  }

  private List<Path> folders(Path workspace) {
    Path root = workerRoot(workspace);
    if (!Files.isDirectory(root)) return List.of();
    try (var paths = Files.list(root)) {
      return paths
          .filter(Files::isDirectory)
          .filter(path -> Files.isRegularFile(path.resolve("metadata.json")))
          .sorted()
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot list Workers", exception);
    }
  }

  private static Path workerRoot(Path workspace) {
    return workspace.toAbsolutePath().normalize().resolve("worker");
  }

  private static String key(Path workspace, String workerId) {
    return workspace.toAbsolutePath().normalize() + "::" + workerId;
  }

  private static Path path(ObjectNode metadata, String field) {
    String value = metadata.path(field).asString("");
    if (value.isBlank()) return null;
    return Path.of(metadata.path("workspace_path").asString()).resolve(value).normalize();
  }

  private static String tail(Path path, int lines) {
    if (path == null || !Files.isRegularFile(path)) return "";
    try {
      List<String> values = Files.readAllLines(path);
      return String.join(
          System.lineSeparator(),
          values.subList(Math.max(0, values.size() - lines), values.size()));
    } catch (IOException exception) {
      return "";
    }
  }

  private static Map<String, Object> resources(long pid) {
    Optional<ProcessHandle> process = pid > 0 ? ProcessHandle.of(pid) : Optional.empty();
    return Map.of(
        "pid", pid,
        "alive", process.map(ProcessHandle::isAlive).orElse(false),
        "cpu_millis",
            process
                .flatMap(value -> value.info().totalCpuDuration())
                .map(Duration::toMillis)
                .orElse(0L),
        "sampled_at", Instant.now().toString());
  }

  private static List<DependencyResolver.Request> dependencyRequests(JsonNode values) {
    List<DependencyResolver.Request> result = new ArrayList<>();
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

  private static Set<String> strings(JsonNode values) {
    java.util.HashSet<String> result = new java.util.HashSet<>();
    for (JsonNode value : values) result.add(value.asString());
    return Set.copyOf(result);
  }

  private static long number(Object value) {
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static String text(JsonNode value, String field, String fallback) {
    return value.path(field).asString(fallback);
  }

  private static String slug(String value) {
    String result =
        value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[._-]+|[._-]+$", "");
    return result.isBlank() ? "worker" : result.substring(0, Math.min(result.length(), 48));
  }

  private static String javaClass(String value) {
    StringBuilder result = new StringBuilder("Worker");
    boolean uppercase = true;
    for (char character : value.toCharArray()) {
      if (!Character.isLetterOrDigit(character)) uppercase = true;
      else {
        result.append(uppercase ? Character.toUpperCase(character) : character);
        uppercase = false;
      }
    }
    return result.toString();
  }

  private static String template(String entryClass) {
    int dot = entryClass.lastIndexOf('.');
    String packageLine = dot < 0 ? "" : "package " + entryClass.substring(0, dot) + ";\n\n";
    String className = dot < 0 ? entryClass : entryClass.substring(dot + 1);
    return packageLine
        + "import java.time.Duration;\n"
        + "import java.util.Map;\n"
        + "import org.opengis.script.sdk.OpenGisWorker;\n"
        + "import org.opengis.script.sdk.WorkerContext;\n\n"
        + "public final class "
        + className
        + " implements OpenGisWorker {\n"
        + "  private volatile boolean stopped;\n"
        + "  public void start(WorkerContext context) throws Exception {\n"
        + "    long sequence = 0;\n"
        + "    while (!stopped) {\n"
        + "      context.checkCancelled();\n"
        + "      context.progress().emit(0.0, \"heartbeat \" + sequence++);\n"
        + "      context.sleep(Duration.ofSeconds(5));\n"
        + "    }\n"
        + "  }\n"
        + "  public void stop() { stopped = true; }\n"
        + "  public Map<String,Object> health() { return Map.of(\"status\", stopped ? \"stopped\" : \"running\"); }\n"
        + "}\n";
  }

  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException exception) {
                  throw new IllegalStateException(
                      "Cannot delete Worker package: " + path, exception);
                }
              });
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot delete Worker package", exception);
    }
  }

  @Override
  public void close() {
    active.values().forEach(worker -> worker.cancellation.cancel());
    executor.shutdownNow();
  }

  private static final class ActiveWorker {
    private final Path workspace;
    private final Path folder;
    private final String workerId;
    private final CancellationToken cancellation;
    private final ScriptCallbacks toolCallbacks;
    private final WorkerEventSink events;
    private final ObjectNode metadata;
    private final ConcurrentHashMap<String, Long> layerSequences;
    private volatile CompletableFuture<Void> future;
    private volatile boolean userPaused;

    private ActiveWorker(
        Path workspace,
        Path folder,
        String workerId,
        CancellationToken cancellation,
        ScriptCallbacks toolCallbacks,
        WorkerEventSink events,
        ObjectNode metadata,
        ConcurrentHashMap<String, Long> layerSequences) {
      this.workspace = workspace;
      this.folder = folder;
      this.workerId = workerId;
      this.cancellation = cancellation;
      this.toolCallbacks = toolCallbacks;
      this.events = events;
      this.metadata = metadata;
      this.layerSequences = layerSequences;
    }

    private boolean isRunning() {
      String status = metadata.path("status").asString();
      return "starting".equals(status) || "running".equals(status) || "restarting".equals(status);
    }
  }
}
