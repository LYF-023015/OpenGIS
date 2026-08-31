/** 文件职责：code 后端领域：提供聚焦的辅助函数。 */
package org.opengis.automation.code.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Small filesystem and process operations shared by script execution components. */
final class ScriptRunSupport {
  static final long MAX_LOG_BYTES = 16L * 1024 * 1024;
  static final long INLINE_LOG_BYTES = 64L * 1024;

  private ScriptRunSupport() {}

  static void destroyProcessTree(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  static void appendBounded(Path path, String text) {
    try {
      Files.createDirectories(path.getParent());
      long existing = Files.exists(path) ? Files.size(path) : 0;
      if (existing >= MAX_LOG_BYTES) return;
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      int length = (int) Math.min(bytes.length, MAX_LOG_BYTES - existing);
      Files.write(
          path,
          java.util.Arrays.copyOf(bytes, length),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // Log persistence failure must not deadlock the child protocol.
    }
  }

  static void writeBounded(Path path, String text) {
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(
          path,
          text.substring(0, Math.min(text.length(), (int) MAX_LOG_BYTES)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot persist script log", exception);
    }
  }

  static String sha256(String source) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  static String sha256(Path path) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  static long size(Path path) {
    try {
      return Files.exists(path) ? Files.size(path) : 0;
    } catch (IOException ignored) {
      return 0;
    }
  }

  static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best-effort cleanup; the run record still explains terminal state.
                }
              });
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }
}
