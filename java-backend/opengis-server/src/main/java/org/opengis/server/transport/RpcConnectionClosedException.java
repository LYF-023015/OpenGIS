package org.opengis.server.transport;

/** Raised for every outstanding Java-to-UI request when its socket closes. */
public class RpcConnectionClosedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public RpcConnectionClosedException(String connectionId) {
    super("WebSocket connection closed: " + connectionId);
  }
}
