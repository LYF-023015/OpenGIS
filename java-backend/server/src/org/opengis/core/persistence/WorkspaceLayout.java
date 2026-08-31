/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.persistence;

import java.nio.file.Path;

/** Resolves every persistent path relative to one trusted workspace root. */
public final class WorkspaceLayout {
  private final Path workspaceRoot;
  private final Path openGisRoot;

  public WorkspaceLayout(Path workspaceRoot) {
    if (workspaceRoot == null) {
      throw new IllegalArgumentException("Workspace path is required");
    }
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    this.openGisRoot = this.workspaceRoot.resolve(".opengis").normalize();
  }

  public Path workspaceRoot() {
    return workspaceRoot;
  }

  public Path openGisRoot() {
    return openGisRoot;
  }

  public Path resolve(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("Relative store path is required");
    }
    Path relative = Path.of(relativePath);
    if (relative.isAbsolute()) {
      throw new WorkspaceStoreException("Absolute store paths are forbidden: " + relativePath);
    }
    Path resolved = openGisRoot.resolve(relative).normalize();
    if (!resolved.startsWith(openGisRoot)) {
      throw new WorkspaceStoreException("Store path escapes .opengis: " + relativePath);
    }
    return resolved;
  }
}
