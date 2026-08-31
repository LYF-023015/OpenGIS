/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.git;

/** Raised when the configured git executable cannot be launched. */
public class GitNotAvailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public GitNotAvailableException(String executable, Throwable cause) {
    super("Git executable is not available: " + executable, cause);
  }
}
