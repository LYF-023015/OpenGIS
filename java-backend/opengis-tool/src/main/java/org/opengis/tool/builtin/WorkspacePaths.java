package org.opengis.tool.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.ToolExecutionContext;

final class WorkspacePaths {
  private WorkspacePaths() {}

  static Path resolve(ToolExecutionContext context, String rawPath) {
    Path raw = Path.of(rawPath);
    Path candidate =
        (raw.isAbsolute() ? raw : context.workspace().resolve(raw)).toAbsolutePath().normalize();
    if (!candidate.startsWith(context.workspace())) {
      throw new ToolException(
          "workspace_boundary", "Tool path must remain inside workspace: " + rawPath);
    }
    verifyExistingAncestor(context.workspace(), candidate);
    return candidate;
  }

  private static void verifyExistingAncestor(Path workspace, Path candidate) {
    Path cursor = candidate;
    while (cursor != null && !Files.exists(cursor)) {
      cursor = cursor.getParent();
    }
    if (cursor == null) {
      return;
    }
    try {
      Path realWorkspace = workspace.toRealPath();
      Path realAncestor = cursor.toRealPath();
      if (!realAncestor.startsWith(realWorkspace)) {
        throw new ToolException("workspace_boundary", "Symbolic link escapes workspace");
      }
    } catch (IOException exception) {
      throw new ToolException("path_resolution_failed", "Cannot resolve workspace path", exception);
    }
  }
}
