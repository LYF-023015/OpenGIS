package org.opengis.common.protocol;

/** Geometry values frozen by the OpenGIS 3.0 protocol. */
public enum GeometryType {
  POINT("Point"),
  MULTI_POINT("MultiPoint"),
  LINE_STRING("LineString"),
  MULTI_LINE_STRING("MultiLineString"),
  POLYGON("Polygon"),
  MULTI_POLYGON("MultiPolygon"),
  GEOMETRY_COLLECTION("GeometryCollection"),
  RASTER("Raster");

  private final String wireValue;

  GeometryType(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
