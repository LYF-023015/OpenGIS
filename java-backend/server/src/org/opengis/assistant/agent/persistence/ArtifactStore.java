/** 文件职责：agent 后端领域：管理状态或持久化数据。 */
package org.opengis.assistant.agent.persistence;

import java.nio.file.Path;
import java.util.List;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceLayout;
import org.opengis.core.security.SensitiveDataRedactor;
import tools.jackson.databind.node.ObjectNode;

/** Append-only workspace artifact index. */
public class ArtifactStore {
  private final JsonFileStore files = new JsonFileStore();
  private final Path path;

  public ArtifactStore(Path workspaceRoot) {
    path = new WorkspaceLayout(workspaceRoot).resolve("artifacts.jsonl");
  }

  public void append(ObjectNode artifact) {
    files.append(path, SensitiveDataRedactor.redact(artifact));
  }

  public List<ObjectNode> list() {
    return files.readJsonLines(path);
  }
}
