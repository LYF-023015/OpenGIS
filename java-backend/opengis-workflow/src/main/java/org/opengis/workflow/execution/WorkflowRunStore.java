package org.opengis.workflow.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Atomic per-run workflow state under .opengis/workflow_runs. */
public final class WorkflowRunStore {
  private final JsonFileStore files;
  private final WorkspaceLayout layout;

  public WorkflowRunStore(Path workspace) {
    files = new JsonFileStore();
    layout = new WorkspaceLayout(workspace);
  }

  public synchronized void save(WorkflowRunSnapshot snapshot) {
    files.write(path(snapshot.runId()), files.objectMapper().valueToTree(snapshot));
  }

  public synchronized Optional<WorkflowRunSnapshot> load(String runId) {
    Path path = path(runId);
    if (!Files.exists(path)) return Optional.empty();
    ObjectMapper mapper = files.objectMapper();
    ObjectNode root = files.readObject(path);
    return Optional.of(mapper.convertValue(root, WorkflowRunSnapshot.class));
  }

  private Path path(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe workflow run id: " + runId);
    }
    return layout.resolve("workflow_runs/" + runId + ".json");
  }
}
