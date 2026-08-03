package org.opengis.agent.session;

public final class SessionBusyException extends RuntimeException {
  public SessionBusyException(String message) {
    super(message);
  }
}
