package org.opengis.agent.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import org.opengis.platform.persistence.WorkspaceStoreException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Python-compatible per-run append-only archive. */
public final class RunArchive {
  public static final List<String> STREAMS =
      List.of(
          "steps.jsonl",
          "tool_calls.jsonl",
          "artifacts.jsonl",
          "events.jsonl",
          "message_parts.jsonl",
          "llm_usage.jsonl");

  private static final List<String> OPEN_STATUSES = List.of("pending", "running", "streaming");

  private final String runId;
  private final Path runDirectory;
  private final JsonFileStore files;
  private ObjectNode meta;

  private RunArchive(String runId, Path runDirectory, JsonFileStore files, ObjectNode meta) {
    this.runId = runId;
    this.runDirectory = runDirectory;
    this.files = files;
    this.meta = meta;
  }

  public static RunArchive open(
      Path workspaceRoot, String runId, String prompt, String model, Path scriptsDirectory) {
    WorkspaceLayout layout = new WorkspaceLayout(workspaceRoot);
    JsonFileStore files = new JsonFileStore();
    Path runDirectory = layout.resolve("runs/" + safeId(runId));
    ObjectNode meta = files.objectMapper().createObjectNode();
    meta.put("run_id", runId);
    meta.put("status", "running");
    meta.put("prompt", prompt == null ? "" : prompt);
    meta.put("workspace_path", layout.workspaceRoot().toString());
    meta.put("model", model == null ? "" : model);
    meta.putNull("pre_sha");
    meta.putNull("post_sha");
    if (scriptsDirectory == null) {
      meta.putNull("scripts_dir");
    } else {
      meta.put("scripts_dir", scriptsDirectory.toString());
    }
    meta.put("created_at", OffsetDateTime.now().toString());
    meta.putNull("finished_at");
    meta.put("step_count", 0);
    meta.putArray("risky_ops");
    meta.putNull("error");
    RunArchive archive = new RunArchive(runId, runDirectory, files, meta);
    archive.flushMeta();
    try {
      Files.createDirectories(runDirectory);
      for (String stream : STREAMS) {
        Path streamPath = runDirectory.resolve(stream);
        if (!Files.exists(streamPath)) {
          Files.createFile(streamPath);
        }
      }
    } catch (IOException exception) {
      throw new WorkspaceStoreException(
          "Cannot initialize run archive: " + runDirectory, exception);
    }
    return archive;
  }

  public synchronized void appendStep(ObjectNode step) {
    append("steps.jsonl", step);
    meta.put("step_count", meta.path("step_count").asInt() + 1);
    flushMeta();
  }

  public synchronized void appendToolCall(ObjectNode toolCall) {
    append("tool_calls.jsonl", toolCall);
  }

  public synchronized void appendArtifact(ObjectNode artifact) {
    append("artifacts.jsonl", artifact);
  }

  public synchronized void appendEvent(ObjectNode event) {
    append("events.jsonl", event);
  }

  public synchronized void appendMessagePart(ObjectNode part) {
    append("message_parts.jsonl", part);
  }

  public synchronized void appendLlmUsage(ObjectNode usage) {
    append("llm_usage.jsonl", usage);
  }

  public synchronized void setPreSha(String sha) {
    putNullable(meta, "pre_sha", sha);
    flushMeta();
  }

  public synchronized void setPostSha(String sha) {
    putNullable(meta, "post_sha", sha);
    flushMeta();
  }

  public synchronized void close(String status, String finalAnswer, String error) {
    repairOpenRecords(status, error);
    meta.put("status", status);
    meta.put("finished_at", OffsetDateTime.now().toString());
    putNullable(meta, "error", error);
    if (finalAnswer != null && !finalAnswer.isBlank()) {
      files.writeText(runDirectory.resolve("final_answer.md"), finalAnswer);
    }
    flushMeta();
  }

  public ObjectNode meta() {
    return meta.deepCopy();
  }

  public String finalAnswer() {
    return files.readText(runDirectory.resolve("final_answer.md"));
  }

  public List<ObjectNode> read(String stream) {
    if (!STREAMS.contains(stream)) {
      throw new IllegalArgumentException("Unknown run stream: " + stream);
    }
    return files.readJsonLines(runDirectory.resolve(stream));
  }

  public static Optional<RunArchive> load(Path workspaceRoot, String runId) {
    WorkspaceLayout layout = new WorkspaceLayout(workspaceRoot);
    Path runDirectory = layout.resolve("runs/" + safeId(runId));
    Path metaPath = runDirectory.resolve("meta.json");
    if (!Files.exists(metaPath)) {
      return Optional.empty();
    }
    JsonFileStore files = new JsonFileStore();
    return Optional.of(new RunArchive(runId, runDirectory, files, files.readObject(metaPath)));
  }

  public static List<RunIndex> list(Path workspaceRoot) {
    Path root = new WorkspaceLayout(workspaceRoot).resolve("runs");
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<RunIndex> rows = new ArrayList<>();
    try (var directories = Files.list(root)) {
      for (Path directory : directories.filter(Files::isDirectory).toList()) {
        Path metaPath = directory.resolve("meta.json");
        if (!Files.exists(metaPath)) {
          continue;
        }
        ObjectNode meta = new JsonFileStore().readObject(metaPath);
        rows.add(toIndex(meta));
      }
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot list run archives: " + root, exception);
    }
    rows.sort(Comparator.comparing(RunIndex::createdAt).reversed());
    return rows;
  }

  private void append(String stream, ObjectNode value) {
    ObjectNode entry = value.deepCopy();
    if (!entry.has("ts")) {
      entry.put("ts", OffsetDateTime.now().toString());
    }
    files.append(runDirectory.resolve(stream), entry);
  }

  private void repairOpenRecords(String runStatus, String error) {
    Map<String, ObjectNode> tools = latest("tool_calls.jsonl", "call_id");
    for (ObjectNode tool : tools.values()) {
      if (!OPEN_STATUSES.contains(tool.path("status").asText())) {
        continue;
      }
      ObjectNode terminal = tool.deepCopy();
      boolean success = "success".equals(runStatus) || "completed".equals(runStatus);
      terminal.put("status", success ? "completed" : "error");
      if (!success) {
        terminal.put(
            "error",
            error == null || error.isBlank()
                ? "run_closed_before_tool_result: run status=" + runStatus
                : error);
      }
      ObjectNode metadata =
          terminal.path("metadata").isObject()
              ? (ObjectNode) terminal.path("metadata").deepCopy()
              : files.objectMapper().createObjectNode();
      metadata.put("finalized_by_run_close", true);
      metadata.put("run_close_status", runStatus);
      terminal.set("metadata", metadata);
      appendToolCall(terminal);
    }

    Map<String, ObjectNode> parts = latest("message_parts.jsonl", "id");
    for (ObjectNode part : parts.values()) {
      if (!OPEN_STATUSES.contains(part.path("status").asText())) {
        continue;
      }
      ObjectNode terminal = part.deepCopy();
      String terminalStatus =
          "success".equals(runStatus) || "completed".equals(runStatus)
              ? "completed"
              : "cancelled".equals(runStatus) ? "cancelled" : "failed";
      terminal.put("status", terminalStatus);
      ObjectNode data =
          terminal.path("data").isObject()
              ? (ObjectNode) terminal.path("data").deepCopy()
              : files.objectMapper().createObjectNode();
      data.put("finalized_by_run_close", true);
      data.put("run_close_status", runStatus);
      terminal.set("data", data);
      appendMessagePart(terminal);
    }
  }

  private Map<String, ObjectNode> latest(String stream, String idField) {
    Map<String, ObjectNode> latest = new LinkedHashMap<>();
    for (ObjectNode row : read(stream)) {
      String id = row.path(idField).asText();
      if (!id.isBlank()) {
        latest.put(id, row);
      }
    }
    return latest;
  }

  private void flushMeta() {
    files.write(runDirectory.resolve("meta.json"), meta);
  }

  private static RunIndex toIndex(JsonNode meta) {
    return new RunIndex(
        meta.path("run_id").asText(),
        meta.path("status").asText("unknown"),
        meta.path("prompt").asText(),
        meta.path("created_at").asText(),
        textOrNull(meta.get("finished_at")),
        meta.path("step_count").asInt(),
        textOrNull(meta.get("pre_sha")),
        textOrNull(meta.get("post_sha")));
  }

  private static String textOrNull(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private static void putNullable(ObjectNode target, String field, String value) {
    if (value == null) {
      target.putNull(field);
    } else {
      target.put(field, value);
    }
  }

  private static String safeId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe run id: " + value);
    }
    return value;
  }
}
