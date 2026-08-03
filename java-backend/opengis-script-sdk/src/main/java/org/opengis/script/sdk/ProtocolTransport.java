package org.opengis.script.sdk;

import java.util.Map;

/** Child-side transport implemented by the bundled host, not by user scripts. */
public interface ProtocolTransport {
  Map<String, Object> request(String type, Map<String, Object> payload);

  void emit(String type, Map<String, Object> payload);

  boolean isCancelled();
}
