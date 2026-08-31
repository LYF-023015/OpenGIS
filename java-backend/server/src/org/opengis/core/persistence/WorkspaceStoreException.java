/** 文件职责：platform 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.persistence;

/** Signals a corrupt, unsafe or inaccessible workspace persistence file. */
public class WorkspaceStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public WorkspaceStoreException(String message) {
    super(message);
  }

  public WorkspaceStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
