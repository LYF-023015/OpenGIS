/** 文件职责：code 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.code.runner;

import static org.opengis.automation.code.runner.ScriptRunSupport.INLINE_LOG_BYTES;
import static org.opengis.automation.code.runner.ScriptRunSupport.appendBounded;
import static org.opengis.automation.code.runner.ScriptRunSupport.destroyProcessTree;
import static org.opengis.automation.code.runner.ScriptRunSupport.sha256;
import static org.opengis.automation.code.runner.ScriptRunSupport.size;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.opengis.automation.code.dependency.DependencyResolver;
import org.opengis.core.persistence.JsonTypeReferences;
import org.opengis.script.sdk.ScriptProtocol;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owns one child JVM protocol conversation and projects it into a script run result. */
final class ScriptProtocolSession {
  private final ObjectMapper mapper;
  private final ScriptRunRequest request;
  private final ScriptCallbacks callbacks;
  private final Process process;
  private final BufferedWriter childInput;
  private final Path stdout;
  private final Path stderr;
  private final CompletableFuture<Void> terminal = new CompletableFuture<>();
  private final AtomicLong lastSequence = new AtomicLong();
  private final AtomicBoolean terminalSet = new AtomicBoolean();
  private final List<Map<String, Object>> artifacts = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> progress = new CopyOnWriteArrayList<>();
  private volatile String status = "running";
  private volatile Object output = Map.of();
  private volatile String error = "";

  ScriptProtocolSession(
      ObjectMapper mapper,
      ScriptRunRequest request,
      ScriptCallbacks callbacks,
      Process process,
      BufferedWriter childInput,
      Path stdout,
      Path stderr) {
    this.mapper = mapper;
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
      destroyProcessTree(process);
      return;
    }
    long sequence = message.path("sequence").asLong();
    long previous = lastSequence.getAndSet(sequence);
    if (sequence <= previous) {
      fail("protocol_error", "Child sequence is not monotonic");
      destroyProcessTree(process);
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
        destroyProcessTree(process);
      }
    }
  }

  private void reply(String callId, java.util.concurrent.Callable<Map<String, Object>> action) {
    try {
      sendResult(callId, action.call());
    } catch (Exception exception) {
      ObjectNode message = baseResult(callId);
      message.put("success", false);
      message.put(
          "error",
          exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
      write(message);
    }
  }

  private void sendResult(String callId, Map<String, Object> payload) {
    ObjectNode message = baseResult(callId);
    message.put("success", true);
    message.set("payload", mapper.valueToTree(payload));
    write(message);
  }

  private ObjectNode baseResult(String callId) {
    ObjectNode message = mapper.createObjectNode();
    message.put("protocol_version", ScriptProtocol.VERSION);
    message.put("type", "request_result");
    message.put("run_id", request.runId());
    message.put("call_id", callId);
    return message;
  }

  private void write(ObjectNode message) {
    synchronized (childInput) {
      try {
        childInput.write(mapper.writeValueAsString(message));
        childInput.newLine();
        childInput.flush();
      } catch (IOException exception) {
        fail("protocol_error", exception.getMessage());
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

  void fail(String terminalStatus, String terminalError) {
    complete(terminalStatus, Map.of(), terminalError == null ? terminalStatus : terminalError);
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
}
