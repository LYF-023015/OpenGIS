/** 文件职责：code 后端领域：承载该领域的核心业务流程。 */
package org.opengis.automation.code.runner;

import static org.opengis.automation.code.runner.ScriptRunSupport.INLINE_LOG_BYTES;
import static org.opengis.automation.code.runner.ScriptRunSupport.appendBounded;
import static org.opengis.automation.code.runner.ScriptRunSupport.deleteTree;
import static org.opengis.automation.code.runner.ScriptRunSupport.destroyProcessTree;
import static org.opengis.automation.code.runner.ScriptRunSupport.sha256;
import static org.opengis.automation.code.runner.ScriptRunSupport.writeBounded;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opengis.automation.code.archive.JavaCodeArchive;
import org.opengis.automation.code.compiler.JavaCompilationService;
import org.opengis.automation.code.dependency.DependencyResolver;
import org.opengis.automation.code.host.ScriptHostMain;
import org.opengis.automation.code.validation.JavaSourceValidator;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceLayout;
import org.opengis.script.sdk.ScriptProtocol;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Compile-and-run service for one Java source in a separately cancellable JVM. */
public final class JavaCodeRunner {
  private static final int MAX_PROTOCOL_LINE = 1_048_576;
  private static final int MAX_RUN_FILES = 500;

  private final ObjectMapper mapper;
  private final JavaSourceValidator sourceValidator = new JavaSourceValidator();
  private final JavaCompilationService compiler = new JavaCompilationService();
  private final DependencyResolver dependencies = new DependencyResolver();

  public JavaCodeRunner(ObjectMapper mapper) {
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
      JavaRuntimeClasspath.Resolved classpath = JavaRuntimeClasspath.resolve(temporary, resolved);
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
      new JavaCodeArchive(request.workspace())
          .archive(
              request.semanticName(),
              request.entryClass(),
              request.source(),
              request.runId(),
              resolved.stream().map(DependencyResolver.ResolvedDependency::coordinate).toList(),
              Map.of("permissions", request.permissions()));
      JavaRuntimeClasspath.Resolved classpath = JavaRuntimeClasspath.resolve(temporary, resolved);
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
      builder.environment().keySet().removeIf(JavaCodeRunner::sensitiveEnvironment);
      process = builder.start();
      Process child = process;
      try (AutoCloseable ignored =
          request.cancellation().onCancel(() -> destroyProcessTree(child))) {
        BufferedWriter childInput =
            new BufferedWriter(
                new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        ScriptProtocolSession state =
            new ScriptProtocolSession(
                mapper, request, activeCallbacks, child, childInput, stdout, stderr);
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
          destroyProcessTree(child);
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
          destroyProcessTree(child);
        }
        child.waitFor(5, TimeUnit.SECONDS);
        if (child.isAlive()) destroyProcessTree(child);
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
      if (process != null) destroyProcessTree(process);
      throw new IllegalStateException("Script execution was interrupted", exception);
    } catch (Exception exception) {
      if (process != null) destroyProcessTree(process);
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

  private void readProtocol(Process process, ScriptProtocolSession state) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.length() > MAX_PROTOCOL_LINE) {
          state.fail("protocol_error", "Child protocol frame exceeds limit");
          destroyProcessTree(process);
          return;
        }
        state.accept(mapper.readTree(line));
      }
      if (!state.terminal().isDone())
        state.fail("child_eof", "Child process closed without terminal event");
    } catch (Exception exception) {
      state.fail("protocol_error", exception.getMessage());
      destroyProcessTree(process);
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

  private static List<String> childCommand(
      int heapMb, String entryClass, Path classes, JavaRuntimeClasspath.Resolved classpath) {
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

  public record CodeValidationResult(
      boolean ok,
      JavaSourceValidator.ValidationResult sourceValidation,
      List<String> compilerDiagnostics,
      List<String> resolvedDependencies) {}
}
