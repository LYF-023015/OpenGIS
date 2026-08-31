/** 文件职责：agent 后端领域：管理状态或持久化数据。 */
package org.opengis.assistant.agent.persistence;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ObjectNode;

/** Persists agent-authored scripts with a sibling metadata record and JSONL index. */
public class ScriptArchive {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final String runId;
  private final Path workspaceRoot;
  private final Path scriptDirectory;
  private final JsonFileStore files = new JsonFileStore();

  public ScriptArchive(Path workspaceRoot, String runId) {
    this.workspaceRoot = new WorkspaceLayout(workspaceRoot).workspaceRoot();
    this.scriptDirectory = this.workspaceRoot.resolve("script");
    this.runId = runId;
  }

  public Path writeStep(int step, String semanticName, String code, Map<String, Object> metadata) {
    String slug = slug(semanticName);
    String baseName =
        FILE_TIME.format(LocalDateTime.now()) + "-step" + String.format("%02d", step) + "-" + slug;
    Path script = scriptDirectory.resolve(baseName + ".py");
    Path metadataPath = scriptDirectory.resolve(baseName + ".metadata.json");
    ObjectNode record = files.objectMapper().valueToTree(metadata);
    record.put("run_id", runId);
    record.put("step", step);
    record.put("semantic_name", semanticName);
    record.put("script_path", script.toString());
    record.put("timestamp", LocalDateTime.now().toString());
    String header = "# OpenGIS Agent run " + runId + " step " + step + "\n";
    files.writeText(script, header + code.stripTrailing() + "\n");
    files.write(metadataPath, record);
    files.append(scriptDirectory.resolve("_scripts_index.jsonl"), record);
    return script;
  }

  public Path runDirectory() {
    return workspaceRoot.resolve(".opengis").resolve("runs").resolve(runId);
  }

  private static String slug(String value) {
    String normalized =
        value == null ? "script" : value.strip().replaceAll("[^\\p{L}\\p{N}._-]+", "-");
    normalized = normalized.replaceAll("-{2,}", "-").replaceAll("^[._-]+|[._-]+$", "");
    return normalized.isBlank()
        ? "script"
        : normalized.substring(0, Math.min(64, normalized.length()));
  }
}
