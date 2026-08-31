/** 文件职责：platform 后端领域：封装外部系统或通信协议。 */
package org.opengis.core.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Git-backed snapshot and explicit hard-revert adapter for workspace safety. */
public class GitWorkspaceAdapter {
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final String gitExecutable;

  public GitWorkspaceAdapter() {
    this("git");
  }

  public GitWorkspaceAdapter(String gitExecutable) {
    this.gitExecutable = gitExecutable;
  }

  public String version() {
    return run(null, "--version").strip();
  }

  public String snapshot(Path workspaceRoot, String runId, String label) {
    Path workspace = requireWorkspace(workspaceRoot);
    requireRepository(workspace);
    run(workspace, "add", "-A");
    String message = "[OpenGIS] " + safeLabel(runId) + " " + safeLabel(label);
    run(
        workspace,
        "-c",
        "user.name=OpenGIS",
        "-c",
        "user.email=opengis@localhost",
        "commit",
        "--allow-empty",
        "--quiet",
        "-m",
        message.strip());
    return head(workspace);
  }

  public String revertHard(Path workspaceRoot, String sha) {
    Path workspace = requireWorkspace(workspaceRoot);
    requireRepository(workspace);
    if (sha == null || !sha.matches("[0-9a-fA-F]{4,64}")) {
      throw new IllegalArgumentException("Invalid git SHA: " + sha);
    }
    run(workspace, "reset", "--hard", sha);
    return head(workspace);
  }

  public String head(Path workspaceRoot) {
    return run(requireWorkspace(workspaceRoot), "rev-parse", "--short", "HEAD").strip();
  }

  private void requireRepository(Path workspace) {
    if (!"true".equals(run(workspace, "rev-parse", "--is-inside-work-tree").strip())) {
      throw new GitCommandException("rev-parse --is-inside-work-tree", 1, "Not a git repository");
    }
  }

  private String run(Path workspace, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(gitExecutable);
    command.addAll(List.of(arguments));
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    if (workspace != null) {
      processBuilder.directory(workspace.toFile());
    }
    try {
      Process process = processBuilder.start();
      boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new GitCommandException(String.join(" ", command), -1, "Timed out");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new GitCommandException(String.join(" ", command), process.exitValue(), output);
      }
      return output;
    } catch (IOException exception) {
      throw new GitNotAvailableException(gitExecutable, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GitCommandException(String.join(" ", command), -1, "Interrupted");
    }
  }

  private static Path requireWorkspace(Path workspaceRoot) {
    Path workspace = workspaceRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) {
      throw new IllegalArgumentException("Workspace directory does not exist: " + workspace);
    }
    return workspace;
  }

  private static String safeLabel(String value) {
    return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
  }
}

/** Raised when a git subprocess returns a non-zero exit code. */
class GitCommandException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  GitCommandException(String command, int exitCode, String output) {
    super("Git command failed (exit " + exitCode + "): " + command + "\n" + output.strip());
  }
}
