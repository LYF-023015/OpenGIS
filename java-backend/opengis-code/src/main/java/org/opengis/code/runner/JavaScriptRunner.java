package org.opengis.code.runner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;
import org.opengis.code.archive.JavaScriptArchive;
import org.opengis.code.compiler.JavaCompilationService;
import org.opengis.code.dependency.DependencyResolver;
import org.opengis.code.host.ScriptHostMain;
import org.opengis.code.validation.JavaSourceValidator;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.JsonTypeReferences;
import org.opengis.platform.persistence.WorkspaceLayout;
import org.opengis.script.sdk.ScriptProtocol;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Compile-and-run service for one Java source in a separately cancellable JVM. */
public final class JavaScriptRunner {
  private static final int MAX_PROTOCOL_LINE = 1_048_576;
  private static final long MAX_LOG_BYTES = 16L * 1024 * 1024;
  private static final long INLINE_LOG_BYTES = 64L * 1024;
  private static final int MAX_RUN_FILES = 500;

  private final ObjectMapper mapper;
  private final JavaSourceValidator sourceValidator = new JavaSourceValidator();
  private final JavaCompilationService compiler = new JavaCompilationService();
  private final DependencyResolver dependencies = new DependencyResolver();

  public JavaScriptRunner(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public JavaSourceValidator.ValidationResult validate(
      String source, String entryClass, Set<String> permissions) {
    return sourceValidator.validate(
        source, entryClass, permissions == null ? Set.of() : permissions);
  }

  public CodeValidationResult validateAndCompile(
      Path workspace,
      String source,
      String entryClass,
      Set<String> permissions,
      List<DependencyResolver.Request> requests,
      boolean offline) {
    var validation = validate(source, entryClass, permissions);
    if (!validation.ok()) {
      return new CodeValidationResult(false, validation, List.of(), List.of());
    }
    List<DependencyResolver.ResolvedDependency> resolved =
        dependencies.resolve(workspace, requests, offline);
    Path temporary =
        new WorkspaceLayout(workspace)
            .openGisRoot()
            .resolve("tmp")
            .resolve("validation-" + UUID.randomUUID());
    try {
      Files.createDirectories(temporary);
      RuntimeClasspath classpath = runtimeClasspath(temporary, resolved);
      var compilation =
          compiler.compile(source, entryClass, temporary, classpath.compilerClasspath());
      return new CodeValidationResult(
          compilation.success(),
          validation,
          compilation.diagnostics(),
          resolved.stream().map(DependencyResolver.ResolvedDependency::coordinate).toList());
    } catch (IOException exception) {
      return new CodeValidationResult(
          false,
          validation,
          List.of("Cannot prepare validation: " + exception.getMessage()),
          List.of());
    } finally {
      deleteTree(temporary);
    }
  }

  @SuppressWarnings("try") // The cancellation registration is a lifetime-only resource.
  public ScriptRunResult run(ScriptRunRequest request, ScriptCallbacks callbacks) {
    ScriptCallbacks activeCallbacks =
        callbacks == null ? ScriptCallbacks.disconnected() : callbacks;
    request.cancellation().throwIfCancelled();
    var validation = validate(request.source(), request.entryClass(), request.permissions());
    if (!validation.ok()) {
      throw new IllegalArgumentException("Java source validation failed: " + validation.errors());
    }
    List<DependencyResolver.ResolvedDependency> resolved =
        dependencies.resolve(request.workspace(), request.dependencies(), request.offline());
    Path openGis = new WorkspaceLayout(request.workspace()).openGisRoot();
    Path runDirectory = openGis.resolve("script-runs").resolve(safeId(request.runId()));
    Path temporary = openGis.resolve("tmp").resolve("script-" + UUID.randomUUID());
    Path stdout = runDirectory.resolve("stdout.log");
    Path stderr = runDirectory.resolve("stderr.log");
    Instant started = Instant.now();
    Process process = null;
    try {
      Files.createDirectories(runDirectory);
      Files.createDirectories(temporary);
      new JavaScriptArchive(request.workspace())
          .archive(
              request.semanticName(),
              request.entryClass(),
              request.source(),
              request.runId(),
              resolved.stream().map(DependencyResolver.ResolvedDependency::coordinate).toList(),
              Map.of("permissions", request.permissions()));
      RuntimeClasspath classpath = runtimeClasspath(temporary, resolved);
      var compilation =
          compiler.compile(
              request.source(), request.entryClass(), temporary, classpath.compilerClasspath());
      if (!compilation.success()) {
        ScriptRunResult result =
            failedBeforeStart(request, started, stdout, stderr, compilation.diagnostics());
        persistRun(runDirectory.resolve("run.json"), result);
        return result;
      }
      List<String> command =
          childCommand(
              request.maxHeapMb(), request.entryClass(), compilation.classesDirectory(), classpath);
      ProcessBuilder builder = new ProcessBuilder(command).directory(request.workspace().toFile());
      builder.environment().keySet().removeIf(JavaScriptRunner::sensitiveEnvironment);
      process = builder.start();
      Process child = process;
      try (AutoCloseable ignored = request.cancellation().onCancel(() -> destroyTree(child))) {
        BufferedWriter childInput =
            new BufferedWriter(
                new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        ProtocolState state =
            new ProtocolState(request, activeCallbacks, child, childInput, stdout, stderr);
        Thread protocolReader =
            Thread.ofPlatform()
                .daemon()
                .name("opengis-script-protocol-" + safeId(request.runId()))
                .start(() -> readProtocol(child, state));
        Thread rawError =
            Thread.ofPlatform()
                .daemon()
                .name("opengis-script-raw-stderr-" + safeId(request.runId()))
                .start(() -> drainRawError(child, stderr));
        writeExecute(childInput, request);
        try {
          state.terminal().get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
          state.fail("timeout", "Script exceeded " + request.timeout());
          send(childInput, "cancel", request.runId(), "", Map.of("reason", "timeout"), true);
          destroyTree(child);
        } catch (java.util.concurrent.ExecutionException exception) {
          state.fail(
              "failed",
              exception.getCause() == null
                  ? exception.getMessage()
                  : exception.getCause().getMessage());
        }
        if (request.cancellation().isCancelled()) {
          state.fail("cancelled", "Script was cancelled");
          send(childInput, "cancel", request.runId(), "", Map.of("reason", "cancelled"), true);
          destroyTree(child);
        }
        child.waitFor(5, TimeUnit.SECONDS);
        if (child.isAlive()) destroyTree(child);
        protocolReader.join(1_000);
        rawError.join(1_000);
        ScriptRunResult result =
            state.result(started, child.isAlive() ? -1 : child.exitValue(), resolved);
        persistRun(runDirectory.resolve("run.json"), result);
        enforceFileQuota(runDirectory);
        return result;
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (process != null) destroyTree(process);
      throw new IllegalStateException("Script execution was interrupted", exception);
    } catch (Exception exception) {
      if (process != null) destroyTree(process);
      throw new IllegalStateException("Cannot execute Java script", exception);
    } finally {
      deleteTree(temporary);
    }
  }

  private ScriptRunResult failedBeforeStart(
      ScriptRunRequest request,
      Instant started,
      Path stdout,
      Path stderr,
      List<String> diagnostics) {
    String error = String.join(System.lineSeparator(), diagnostics);
    writeBounded(stderr, error + System.lineSeparator());
    return new ScriptRunResult(
        request.runId(),
        "failed",
        Map.of(),
        error,
        -1,
        started,
        Instant.now(),
        stdout,
        stderr,
        false,
        error.length() > INLINE_LOG_BYTES,
        List.of(),
        List.of(),
        sha256(request.source()),
        List.of());
  }

  private void readProtocol(Process process, ProtocolState state) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.length() > MAX_PROTOCOL_LINE) {
          state.fail("protocol_error", "Child protocol frame exceeds limit");
          destroyTree(process);
          return;
        }
        state.accept(mapper.readTree(line));
      }
      if (!state.terminal().isDone())
        state.fail("child_eof", "Child process closed without terminal event");
    } catch (Exception exception) {
      state.fail("protocol_error", exception.getMessage());
      destroyTree(process);
    }
  }

  private static void drainRawError(Process process, Path stderr) {
    try (var reader = process.getErrorStream()) {
      byte[] buffer = new byte[8_192];
      int read;
      while ((read = reader.read(buffer)) >= 0) {
        appendBounded(stderr, new String(buffer, 0, read, StandardCharsets.UTF_8));
      }
    } catch (IOException ignored) {
      // The protocol state reports child termination; raw stderr draining is best effort.
    }
  }

  private void writeExecute(BufferedWriter writer, ScriptRunRequest request) {
    ObjectNode message = mapper.createObjectNode();
    message.put("protocol_version", ScriptProtocol.VERSION);
    message.put("type", "execute");
    message.put("run_id", request.runId());
    message.put("workspace", request.workspace().toString());
    message.set("parameters", mapper.valueToTree(request.parameters()));
    synchronized (writer) {
      try {
        writer.write(mapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot start child protocol", exception);
      }
    }
  }

  private void send(
      BufferedWriter writer,
      String type,
      String runId,
      String callId,
      Map<String, Object> payload,
      boolean success) {
    ObjectNode message = mapper.createObjectNode();
    message.put("protocol_version", ScriptProtocol.VERSION);
    message.put("type", type);
    message.put("run_id", runId);
    message.put("call_id", callId);
    message.put("success", success);
    message.set("payload", mapper.valueToTree(payload));
    synchronized (writer) {
      try {
        writer.write(mapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
      } catch (IOException exception) {
        throw new IllegalStateException("Cannot reply to child process", exception);
      }
    }
  }

  private RuntimeClasspath runtimeClasspath(
      Path temporary, List<DependencyResolver.ResolvedDependency> resolved) throws IOException {
    List<Path> current =
        java.util.Arrays.stream(
                System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(Path::of)
            .filter(Files::exists)
            .toList();
    List<Path> dependencyJars =
        resolved.stream().map(DependencyResolver.ResolvedDependency::jar).toList();
    if (current.size() == 1
        && Files.isRegularFile(current.getFirst())
        && isBootJar(current.getFirst())) {
      Path sdk = extractNestedSdk(current.getFirst(), temporary);
      List<Path> compiler = new ArrayList<>();
      compiler.add(sdk);
      compiler.addAll(dependencyJars);
      return new RuntimeClasspath(compiler, dependencyJars, current.getFirst());
    }
    List<Path> compiler = new ArrayList<>(current);
    compiler.addAll(dependencyJars);
    return new RuntimeClasspath(compiler, compiler, null);
  }

  private static boolean isBootJar(Path path) {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      return zip.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class")
          != null;
    } catch (IOException exception) {
      return false;
    }
  }

  private static Path extractNestedSdk(Path bootJar, Path temporary) throws IOException {
    try (ZipFile zip = new ZipFile(bootJar.toFile())) {
      var entry =
          zip.stream()
              .filter(value -> value.getName().matches("BOOT-INF/lib/opengis-script-sdk-.*\\.jar"))
              .findFirst()
              .orElseThrow(() -> new IOException("Bundled Script SDK jar is missing"));
      Path target = temporary.resolve("opengis-script-sdk.jar");
      Files.copy(zip.getInputStream(entry), target);
      return target;
    }
  }

  private static List<String> childCommand(
      int heapMb, String entryClass, Path classes, RuntimeClasspath classpath) {
    Path javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java");
    List<String> command =
        new ArrayList<>(List.of(javaExecutable.toString(), "-Xmx" + heapMb + "m"));
    if (classpath.bootJar() != null) {
      List<Path> loader = new ArrayList<>(List.of(classes));
      loader.addAll(classpath.runtimeClasspath());
      command.add("-Dloader.main=" + ScriptHostMain.class.getName());
      command.add(
          "-Dloader.path=" + String.join(",", loader.stream().map(Path::toString).toList()));
      command.addAll(
          List.of(
              "-cp",
              classpath.bootJar().toString(),
              "org.springframework.boot.loader.launch.PropertiesLauncher",
              entryClass));
    } else {
      List<Path> runtime = new ArrayList<>(List.of(classes));
      runtime.addAll(classpath.runtimeClasspath());
      command.addAll(
          List.of(
              "-cp",
              String.join(
                  java.io.File.pathSeparator, runtime.stream().map(Path::toString).toList()),
              ScriptHostMain.class.getName(),
              entryClass));
    }
    return command;
  }

  private void persistRun(Path path, ScriptRunResult result) {
    new JsonFileStore(mapper).write(path, mapper.valueToTree(result));
  }

  private static void enforceFileQuota(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      if (paths.limit(MAX_RUN_FILES + 1L).count() > MAX_RUN_FILES) {
        throw new IllegalStateException("Script run exceeded file-count quota");
      }
    }
  }

  private static boolean sensitiveEnvironment(String name) {
    String upper = name.toUpperCase(java.util.Locale.ROOT);
    return upper.contains("API_KEY")
        || upper.contains("TOKEN")
        || upper.contains("PASSWORD")
        || upper.contains("SECRET");
  }

  private static String safeId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]+"))
      throw new IllegalArgumentException("Unsafe run id");
    return value;
  }

  private static void destroyTree(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static void appendBounded(Path path, String text) {
    try {
      Files.createDirectories(path.getParent());
      long existing = Files.exists(path) ? Files.size(path) : 0;
      if (existing >= MAX_LOG_BYTES) return;
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      int length = (int) Math.min(bytes.length, MAX_LOG_BYTES - existing);
      Files.write(
          path,
          java.util.Arrays.copyOf(bytes, length),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // Log persistence failure must not deadlock the child protocol.
    }
  }

  private static void writeBounded(Path path, String text) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(
          path,
          text.substring(0, Math.min(text.length(), (int) MAX_LOG_BYTES)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot persist script log", exception);
    }
  }

  private static String sha256(String source) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String sha256(Path path) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best-effort cleanup; the run record still explains terminal state.
                }
              });
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }

  private record RuntimeClasspath(
      List<Path> compilerClasspath, List<Path> runtimeClasspath, Path bootJar) {}

  public record CodeValidationResult(
      boolean ok,
      JavaSourceValidator.ValidationResult sourceValidation,
      List<String> compilerDiagnostics,
      List<String> resolvedDependencies) {}

  private final class ProtocolState {
    private final ScriptRunRequest request;
    private final ScriptCallbacks callbacks;
    private final Process process;
    private final BufferedWriter childInput;
    private final Path stdout;
    private final Path stderr;
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();
    private final AtomicLong lastSequence = new AtomicLong();
    private final AtomicBoolean terminalSet = new AtomicBoolean();
    private final List<Map<String, Object>> artifacts =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> progress =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String status = "running";
    private volatile Object output = Map.of();
    private volatile String error = "";

    private ProtocolState(
        ScriptRunRequest request,
        ScriptCallbacks callbacks,
        Process process,
        BufferedWriter childInput,
        Path stdout,
        Path stderr) {
      this.request = request;
      this.callbacks = callbacks;
      this.process = process;
      this.childInput = childInput;
      this.stdout = stdout;
      this.stderr = stderr;
    }

    void accept(JsonNode message) {
      if (!ScriptProtocol.VERSION.equals(message.path("protocol_version").asString())) {
        fail("protocol_error", "Unsupported child protocol version");
        destroyTree(process);
        return;
      }
      long sequence = message.path("sequence").asLong();
      long previous = lastSequence.getAndSet(sequence);
      if (sequence <= previous) {
        fail("protocol_error", "Child sequence is not monotonic");
        destroyTree(process);
        return;
      }
      String type = message.path("type").asString();
      String callId = message.path("call_id").asString();
      Map<String, Object> payload =
          mapper.convertValue(message.path("payload"), JsonTypeReferences.STRING_OBJECT_MAP);
      callbacks.event(type, payload);
      switch (type) {
        case "stdout" ->
            appendBounded(
                stdout, String.valueOf(payload.getOrDefault("text", "")) + System.lineSeparator());
        case "stderr" ->
            appendBounded(
                stderr, String.valueOf(payload.getOrDefault("text", "")) + System.lineSeparator());
        case "progress" -> progress.add(Map.copyOf(payload));
        case "map_event" ->
            callbacks.mapEvent(
                String.valueOf(payload.get("method")),
                mapper.convertValue(
                    payload.getOrDefault("parameters", Map.of()),
                    JsonTypeReferences.STRING_OBJECT_MAP));
        case "tool_call" ->
            reply(
                callId,
                () ->
                    callbacks.callTool(
                        String.valueOf(payload.get("name")),
                        mapper.convertValue(
                            payload.getOrDefault("arguments", Map.of()),
                            JsonTypeReferences.STRING_OBJECT_MAP)));
        case "artifact" -> reply(callId, () -> registerArtifact(payload));
        case "completed" -> complete("completed", payload.getOrDefault("output", Map.of()), "");
        case "failed" ->
            complete(
                "failed", Map.of(), String.valueOf(payload.getOrDefault("error", "Script failed")));
        case "cancelled" ->
            complete(
                "cancelled",
                Map.of(),
                String.valueOf(payload.getOrDefault("error", "Script cancelled")));
        case "started" -> {}
        default -> {
          fail("protocol_error", "Unknown child event: " + type);
          destroyTree(process);
        }
      }
    }

    private void reply(String callId, java.util.concurrent.Callable<Map<String, Object>> action) {
      try {
        send(childInput, "request_result", request.runId(), callId, action.call(), true);
      } catch (Exception exception) {
        ObjectNode message = mapper.createObjectNode();
        message.put("protocol_version", ScriptProtocol.VERSION);
        message.put("type", "request_result");
        message.put("run_id", request.runId());
        message.put("call_id", callId);
        message.put("success", false);
        message.put(
            "error",
            exception.getMessage() == null
                ? exception.getClass().getName()
                : exception.getMessage());
        synchronized (childInput) {
          try {
            childInput.write(mapper.writeValueAsString(message));
            childInput.newLine();
            childInput.flush();
          } catch (IOException ioException) {
            fail("protocol_error", ioException.getMessage());
          }
        }
      }
    }

    private Map<String, Object> registerArtifact(Map<String, Object> payload) throws IOException {
      Path value = Path.of(String.valueOf(payload.get("path")));
      Path path =
          value.isAbsolute() ? value.normalize() : request.workspace().resolve(value).normalize();
      Path workspaceReal = request.workspace().toRealPath();
      Path pathReal = Files.isRegularFile(path) ? path.toRealPath() : path;
      if (!pathReal.startsWith(workspaceReal) || !Files.isRegularFile(pathReal)) {
        throw new IllegalArgumentException("Artifact must be a file inside workspace");
      }
      Map<String, Object> record =
          Map.of(
              "path", workspaceReal.relativize(pathReal).toString().replace('\\', '/'),
              "absolute_path", pathReal.toString(),
              "mime_type",
                  String.valueOf(payload.getOrDefault("mime_type", "application/octet-stream")),
              "title",
                  String.valueOf(payload.getOrDefault("title", pathReal.getFileName().toString())),
              "size", Files.size(pathReal),
              "sha256", sha256(pathReal));
      artifacts.add(record);
      callbacks.event("artifact_registered", record);
      return record;
    }

    void fail(String status, String error) {
      complete(status, Map.of(), error == null ? status : error);
    }

    private void complete(String terminalStatus, Object terminalOutput, String terminalError) {
      if (terminalSet.compareAndSet(false, true)) {
        status = terminalStatus;
        output = terminalOutput;
        error = terminalError;
        terminal.complete(null);
      }
    }

    CompletableFuture<Void> terminal() {
      return terminal;
    }

    ScriptRunResult result(
        Instant started, int exitCode, List<DependencyResolver.ResolvedDependency> resolved) {
      return new ScriptRunResult(
          request.runId(),
          status,
          output,
          error,
          exitCode,
          started,
          Instant.now(),
          stdout,
          stderr,
          size(stdout) > INLINE_LOG_BYTES,
          size(stderr) > INLINE_LOG_BYTES,
          List.copyOf(artifacts),
          List.copyOf(progress),
          sha256(request.source()),
          resolved.stream().map(DependencyResolver.ResolvedDependency::sha256).toList());
    }

    private long size(Path path) {
      try {
        return Files.exists(path) ? Files.size(path) : 0;
      } catch (IOException ignored) {
        return 0;
      }
    }
  }
}
