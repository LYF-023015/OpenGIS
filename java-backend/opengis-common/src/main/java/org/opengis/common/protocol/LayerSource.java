package org.opengis.common.protocol;

/** Supported layer origins frozen by the OpenGIS 3.0 protocol. */
public enum LayerSource {
  FILE("file"),
  MEMORY("memory"),
  URL("url"),
  POSTGIS("postgis");

  private final String wireValue;

  LayerSource(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
