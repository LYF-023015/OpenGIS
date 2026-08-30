package org.opengis.platform.git;

/** Raised when a git subprocess returns a non-zero exit code. */
public class GitCommandException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public GitCommandException(String command, int exitCode, String output) {
    super("Git command failed (exit " + exitCode + "): " + command + "\n" + output.strip());
  }
}
