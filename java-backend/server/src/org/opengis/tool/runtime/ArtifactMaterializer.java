/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.opengis.core.security.SensitiveDataRedactor;
import org.opengis.tool.api.ArtifactRef;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.ObjectMapper;

/** Writes oversized UTF-8 output below .opengis/runs/{run}/artifacts. */
public final class ArtifactMaterializer {
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    byte[] bytes = redact(content, mediaType).getBytes(StandardCharsets.UTF_8);
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

  private static String redact(String content, String mediaType) {
    if (content == null) return "";
    if (mediaType != null && mediaType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
      try {
        return MAPPER.writeValueAsString(SensitiveDataRedactor.redact(MAPPER.readTree(content)));
      } catch (RuntimeException ignored) {
        // Fall through to conservative text redaction for malformed JSON.
      }
    }
    return SensitiveDataRedactor.redactText(content);
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
