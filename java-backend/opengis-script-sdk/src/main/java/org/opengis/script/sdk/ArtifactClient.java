package org.opengis.script.sdk;

import java.nio.file.Path;
import java.util.Map;

/** Registers a file already written inside the workspace as a durable artifact. */
public final class ArtifactClient {
  private final ProtocolTransport transport;

  ArtifactClient(ProtocolTransport transport) {
    this.transport = transport;
  }

  public Map<String, Object> register(Path path, String mimeType, String title) {
    if (path == null) throw new IllegalArgumentException("Artifact path is required");
    return transport.request(
        "artifact",
        Map.of(
            "path", path.toString(),
            "mime_type", mimeType == null ? "application/octet-stream" : mimeType,
            "title", title == null ? path.getFileName().toString() : title));
  }

  /** Registers a PNG/JPEG/SVG plot so connected OpenGIS clients can display it inline. */
  public Map<String, Object> registerPlot(Path path, String title) {
    String name =
        path == null ? "" : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    String mimeType =
        name.endsWith(".svg")
            ? "image/svg+xml"
            : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : "image/png";
    return register(path, mimeType, title);
  }
}
