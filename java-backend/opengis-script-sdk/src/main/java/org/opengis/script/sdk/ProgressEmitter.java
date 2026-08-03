package org.opengis.script.sdk;

import java.util.Map;

/** Sends bounded structured progress independently from stdout. */
public final class ProgressEmitter {
  private final ProtocolTransport transport;

  ProgressEmitter(ProtocolTransport transport) {
    this.transport = transport;
  }

  public void emit(double fraction, String message) {
    if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) {
      throw new IllegalArgumentException("Progress fraction must be between 0 and 1");
    }
    transport.emit(
        "progress", Map.of("fraction", fraction, "message", message == null ? "" : message));
  }
}
