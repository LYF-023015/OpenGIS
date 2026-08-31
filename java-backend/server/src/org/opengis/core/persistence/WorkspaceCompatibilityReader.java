/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Read-only validator for every Phase 0 workspace-local store family. */
public class WorkspaceCompatibilityReader {
  private final JsonFileStore files;

  public WorkspaceCompatibilityReader() {
    this(new JsonFileStore());
  }

  public WorkspaceCompatibilityReader(JsonFileStore files) {
    this.files = files;
  }

  public WorkspaceCompatibilityReport inspect(Path workspaceRoot) {
    WorkspaceLayout layout = new WorkspaceLayout(workspaceRoot);
    List<StoreInspection> stores = new ArrayList<>();
    stores.add(json("sessions-and-inbox", layout.resolve("sessions.json")));
    stores.add(json("agent-profiles", layout.resolve("agents.json")));
    stores.add(json("permissions", layout.resolve("permissions.json")));
    stores.add(globJson("conversation-context", layout.resolve("contexts"), path -> true));
    stores.add(json("titled-conversations", layout.resolve("titled_conversations.json")));
    stores.add(jsonl("artifact-index", layout.resolve("artifacts.jsonl")));
    stores.add(globJsonl("structured-memory", layout.resolve("memory")));
    stores.add(text("legacy-memory", layout.resolve("memory.md")));
    stores.add(
        globJson(
            "workflows",
            layout.resolve("workflows"),
            path -> path.toString().endsWith(".flow.json")));
    stores.add(
        globText(
            "workflow-step-output",
            layout.resolve("workflow_steps"),
            path -> path.toString().endsWith(".md")));
    stores.add(runArchives(layout.resolve("runs")));
    stores.add(operationDefinitions(layout.resolve("operations")));
    stores.add(operationRuns(layout.resolve("operation-runs")));
    stores.add(json("skill-sources", layout.resolve("skill-sources.json")));
    stores.add(
        globText(
            "workspace-skills",
            layout.resolve("skills"),
            path -> path.getFileName().toString().equals("SKILL.md")));
    stores.add(
        new StoreInspection(
            "raster-cache", false, true, 0, 0, "regenerable cache; round-trip excluded"));
    return new WorkspaceCompatibilityReport(List.copyOf(stores));
  }

  private StoreInspection json(String name, Path path) {
    try {
      if (!Files.exists(path)) {
        return missing(name, path);
      }
      files.read(path);
      return ok(name, 1, 1);
    } catch (WorkspaceStoreException exception) {
      return failed(name, exception);
    }
  }

  private StoreInspection jsonl(String name, Path path) {
    try {
      if (!Files.exists(path)) {
        return missing(name, path);
      }
      return ok(name, 1, files.readJsonLines(path).size());
    } catch (WorkspaceStoreException exception) {
      return failed(name, exception);
    }
  }

  private StoreInspection text(String name, Path path) {
    try {
      if (!Files.exists(path)) {
        return missing(name, path);
      }
      files.readText(path);
      return ok(name, 1, 1);
    } catch (WorkspaceStoreException exception) {
      return failed(name, exception);
    }
  }

  private StoreInspection globJson(String name, Path root, Predicate<Path> include) {
    return inspectTree(name, root, include, path -> files.read(path).size());
  }

  private StoreInspection globJsonl(String name, Path root) {
    return inspectTree(
        name,
        root,
        path -> path.toString().endsWith(".jsonl"),
        path -> files.readJsonLines(path).size());
  }

  private StoreInspection globText(String name, Path root, Predicate<Path> include) {
    return inspectTree(
        name,
        root,
        include,
        path -> {
          files.readText(path);
          return 1;
        });
  }

  private StoreInspection runArchives(Path root) {
    return inspectTree(
        "run-archive",
        root,
        path ->
            path.getFileName().toString().equals("meta.json") || path.toString().endsWith(".jsonl"),
        path ->
            path.toString().endsWith(".jsonl")
                ? files.readJsonLines(path).size()
                : files.read(path).size());
  }

  private StoreInspection operationDefinitions(Path root) {
    return inspectTree(
        "workspace-operations",
        root,
        path -> path.getFileName().toString().equals("operation.json"),
        path -> files.read(path).size());
  }

  private StoreInspection operationRuns(Path root) {
    return inspectTree(
        "operation-runs",
        root,
        path -> path.toString().endsWith(".json"),
        path -> files.read(path).size());
  }

  private StoreInspection inspectTree(
      String name, Path root, Predicate<Path> include, RecordCounter counter) {
    if (!Files.exists(root)) {
      return missing(name, root);
    }
    int fileCount = 0;
    int recordCount = 0;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).filter(include).toList()) {
        fileCount++;
        recordCount += counter.count(path);
      }
      if (fileCount == 0) {
        return new StoreInspection(name, true, true, 0, 0, "not present; valid empty store");
      }
      return ok(name, fileCount, recordCount);
    } catch (IOException | WorkspaceStoreException exception) {
      return failed(name, exception);
    }
  }

  private static StoreInspection ok(String name, int files, int records) {
    return new StoreInspection(name, true, true, files, records, "ok");
  }

  private static StoreInspection missing(String name, Path path) {
    return new StoreInspection(name, true, true, 0, 0, "not present: " + path);
  }

  private static StoreInspection failed(String name, Exception exception) {
    return new StoreInspection(name, true, false, 0, 0, exception.getMessage());
  }

  @FunctionalInterface
  private interface RecordCounter {
    int count(Path path);
  }
}
