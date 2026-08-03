package org.opengis.script.sdk;

import java.util.Map;

/** Calls a registered OpenGIS Tool through the parent process and its permission runtime. */
public final class ToolClient {
  private final ProtocolTransport transport;

  ToolClient(ProtocolTransport transport) {
    this.transport = transport;
  }

  public Map<String, Object> call(String name, Map<String, Object> arguments) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name is required");
    return transport.request(
        "tool_call", Map.of("name", name, "arguments", arguments == null ? Map.of() : arguments));
  }
}
