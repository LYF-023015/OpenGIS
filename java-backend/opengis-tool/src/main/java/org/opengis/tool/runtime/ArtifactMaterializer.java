package org.opengis.tool.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.opengis.tool.api.ArtifactRef;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.ToolExecutionContext;

/** Writes oversized UTF-8 output below .opengis/runs/{run}/artifacts. */
public final class ArtifactMaterializer {
  public ArtifactRef writeText(
      ToolExecutionContext context, String toolName, String content, String mediaType) {
    String id = UUID.randomUUID().toString();
    Path directory =
        context
            .workspace()
            .resolve(".opengis")
            .resolve("runs")
            .resolve(safeSegment(context.runId()))
            .resolve("artifacts")
            .normalize();
    Path path = directory.resolve(id + ".txt");
    if (!path.startsWith(context.workspace())) {
      throw new ToolException("artifact_path_invalid", "Artifact path escaped workspace");
    }
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try {
      Files.createDirectories(directory);
      Files.write(path, bytes);
      return new ArtifactRef(
          id,
          toolName + " full output",
          context.workspace().relativize(path).toString().replace('\\', '/'),
          mediaType,
          bytes.length,
          sha256(bytes));
    } catch (IOException exception) {
      throw new ToolException("artifact_write_failed", "Cannot materialize tool output", exception);
    }
  }

  private static String safeSegment(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
