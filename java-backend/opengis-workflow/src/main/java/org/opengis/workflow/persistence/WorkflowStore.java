package org.opengis.workflow.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import org.opengis.workflow.WorkflowCodec;
import org.opengis.workflow.model.WorkflowDocument;
import tools.jackson.databind.node.ObjectNode;

/** Workflow documents and per-node markdown output. */
public class WorkflowStore {
  private final JsonFileStore files = new JsonFileStore();
  private final WorkspaceLayout layout;

  public WorkflowStore(Path workspaceRoot) {
    layout = new WorkspaceLayout(workspaceRoot);
  }

  public Optional<ObjectNode> load(String workflowId) {
    Path path = workflowPath(workflowId);
    return Files.exists(path) ? Optional.of(files.readObject(path)) : Optional.empty();
  }

  public Optional<WorkflowDocument> loadDocument(String workflowId) {
    return load(workflowId).map(value -> new WorkflowCodec(files.objectMapper()).parse(value));
  }

  public Path save(String workflowId, ObjectNode workflow) {
    Path path = workflowPath(workflowId);
    files.write(path, workflow);
    return path;
  }

  public Path save(WorkflowDocument workflow) {
    return save(
        workflow.id(), (ObjectNode) new WorkflowCodec(files.objectMapper()).toJson(workflow));
  }

  public Path saveStepOutput(int stepIndex, String nodeId, String markdown) {
    if (stepIndex < 0 || nodeId == null || !nodeId.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe workflow step identity");
    }
    Path path = layout.resolve("workflow_steps/step" + stepIndex + "_" + nodeId + ".md");
    files.writeText(path, markdown);
    return path;
  }

  public String loadStepOutput(int stepIndex, String nodeId) {
    return files.readText(layout.resolve("workflow_steps/step" + stepIndex + "_" + nodeId + ".md"));
  }

  private Path workflowPath(String workflowId) {
    if (workflowId == null || !workflowId.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe workflow id: " + workflowId);
    }
    return layout.resolve("workflows/" + workflowId + ".flow.json");
  }
}
