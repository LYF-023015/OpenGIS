package org.opengis.agent.session;

public final class SessionBusyException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SessionBusyException(String message) {
    super(message);
  }
}
