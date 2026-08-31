/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.concurrent;

/** Framework-neutral signal raised when cooperative work is cancelled. */
public final class OperationCancelledException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public OperationCancelledException() {
    super("Operation was cancelled");
  }
}
