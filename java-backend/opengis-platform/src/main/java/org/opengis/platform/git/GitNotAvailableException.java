package org.opengis.platform.git;

/** Raised when the configured git executable cannot be launched. */
public class GitNotAvailableException extends RuntimeException {
  public GitNotAvailableException(String executable, Throwable cause) {
    super("Git executable is not available: " + executable, cause);
  }
}
