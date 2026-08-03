package org.opengis.script.sdk;

import java.util.Map;

/** Emits allowlisted map RPC events through the parent process. */
public final class MapClient {
  private final ProtocolTransport transport;

  MapClient(ProtocolTransport transport) {
    this.transport = transport;
  }

  public void emit(String method, Map<String, Object> parameters) {
    if (method == null || !method.startsWith("rpc.ui.map.")) {
      throw new IllegalArgumentException("Only rpc.ui.map.* methods are allowed");
    }
    transport.emit(
        "map_event",
        Map.of("method", method, "parameters", parameters == null ? Map.of() : parameters));
  }
}
