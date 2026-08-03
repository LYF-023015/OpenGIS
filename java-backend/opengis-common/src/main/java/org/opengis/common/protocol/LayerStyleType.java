package org.opengis.common.protocol;

/** MapLibre layer style families frozen by the OpenGIS 3.0 protocol. */
public enum LayerStyleType {
  CIRCLE("circle"),
  LINE("line"),
  FILL("fill"),
  RASTER("raster"),
  SYMBOL("symbol");

  private final String wireValue;

  LayerStyleType(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
