package org.opengis.agent.persistence;

import java.nio.file.Path;
import java.util.List;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ObjectNode;

/** Append-only workspace artifact index. */
public class ArtifactStore {
  private final JsonFileStore files = new JsonFileStore();
  private final Path path;

  public ArtifactStore(Path workspaceRoot) {
    path = new WorkspaceLayout(workspaceRoot).resolve("artifacts.jsonl");
  }

  public void append(ObjectNode artifact) {
    files.append(path, artifact);
  }

  public List<ObjectNode> list() {
    return files.readJsonLines(path);
  }
}
