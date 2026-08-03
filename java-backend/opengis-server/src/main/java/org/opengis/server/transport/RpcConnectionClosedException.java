package org.opengis.server.transport;

/** Raised for every outstanding Java-to-UI request when its socket closes. */
public class RpcConnectionClosedException extends RuntimeException {
  public RpcConnectionClosedException(String connectionId) {
    super("WebSocket connection closed: " + connectionId);
  }
}
