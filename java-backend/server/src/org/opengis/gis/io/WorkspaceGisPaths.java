/** 文件职责：gis 后端领域：提供聚焦的辅助函数。 */
package org.opengis.gis.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.opengis.gis.error.GisException;

/** Resolves GIS inputs and outputs without allowing traversal or symlink escape. */
public final class WorkspaceGisPaths {
  private WorkspaceGisPaths() {}

  public static Path input(Path workspace, String value) {
    Path root = realWorkspace(workspace);
    Path path = resolve(root, value);
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new GisException("gis_file_not_found", "GIS input is not a regular file: " + value);
      }
      Path real = path.toRealPath();
      if (!real.startsWith(root)) {
        throw new GisException("path_outside_workspace", "GIS input escapes workspace");
      }
      return real;
    } catch (IOException exception) {
      throw new GisException("gis_file_not_found", "Cannot resolve GIS input: " + value, exception);
    }
  }

  public static Path output(Path workspace, String value, String defaultRelative) {
    Path root = realWorkspace(workspace);
    Path path = resolve(root, value == null || value.isBlank() ? defaultRelative : value);
    Path parent = path.getParent();
    try {
      Files.createDirectories(parent);
      Path realParent = parent.toRealPath();
      if (!realParent.startsWith(root)) {
        throw new GisException("path_outside_workspace", "GIS output escapes workspace");
      }
      return realParent.resolve(path.getFileName()).normalize();
    } catch (IOException exception) {
      throw new GisException("gis_output_unavailable", "Cannot prepare GIS output", exception);
    }
  }

  private static Path realWorkspace(Path workspace) {
    try {
      Files.createDirectories(workspace);
      return workspace.toRealPath();
    } catch (IOException exception) {
      throw new GisException("workspace_unavailable", "Cannot resolve workspace", exception);
    }
  }

  private static Path resolve(Path root, String value) {
    if (value == null || value.isBlank()) {
      throw new GisException("gis_path_required", "GIS path is required");
    }
    Path requested = Path.of(value);
    Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
    if (!path.startsWith(root)) {
      throw new GisException("path_outside_workspace", "GIS path must stay inside workspace");
    }
    return path;
  }
}
