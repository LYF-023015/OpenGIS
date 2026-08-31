/** 文件职责：worker 后端领域：管理状态或持久化数据。 */
package org.opengis.automation.worker.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceStoreException;
import tools.jackson.databind.node.ObjectNode;

/** Compatible metadata.json reader/writer for resident worker packages. */
public class WorkerMetadataStore {
  private final Path workspaceRoot;
  private final JsonFileStore files = new JsonFileStore();

  public WorkerMetadataStore(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
  }

  public Optional<ObjectNode> load(String folderName) {
    Path path = metadataPath(folderName);
    return Files.exists(path) ? Optional.of(files.readObject(path)) : Optional.empty();
  }

  public void save(String folderName, ObjectNode metadata) {
    files.write(metadataPath(folderName), metadata);
  }

  public List<ObjectNode> list() {
    Path workerRoot = workspaceRoot.resolve("worker");
    if (!Files.isDirectory(workerRoot)) {
      return List.of();
    }
    List<ObjectNode> workers = new ArrayList<>();
    try (var folders = Files.list(workerRoot)) {
      folders
          .filter(Files::isDirectory)
          .map(folder -> folder.resolve("metadata.json"))
          .filter(Files::exists)
          .forEach(path -> workers.add(files.readObject(path)));
      return workers;
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot list worker metadata: " + workerRoot, exception);
    }
  }

  private Path metadataPath(String folderName) {
    if (folderName == null || !folderName.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe worker folder: " + folderName);
    }
    return workspaceRoot.resolve("worker").resolve(folderName).resolve("metadata.json").normalize();
  }
}
