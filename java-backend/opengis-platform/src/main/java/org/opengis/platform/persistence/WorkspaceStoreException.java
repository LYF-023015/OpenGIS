package org.opengis.platform.persistence;

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
