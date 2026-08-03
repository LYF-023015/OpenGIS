package org.opengis.gis.crs;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.opengis.tool.api.ToolException;

/** Centralized longitude-first CRS decoding and geometry transformation. */
public final class CrsService {
  public CoordinateReferenceSystem decode(String code) {
    try {
      return CRS.decode(normalize(code), true);
    } catch (Exception exception) {
      throw new ToolException("invalid_crs", "Cannot decode CRS: " + code, exception);
    }
  }

  public Geometry transform(Geometry geometry, String source, String target) {
    if (normalize(source).equalsIgnoreCase(normalize(target))) {
      return geometry.copy();
    }
    try {
      MathTransform transform = CRS.findMathTransform(decode(source), decode(target), true);
      Geometry output = JTS.transform(geometry, transform);
      output.setSRID(epsg(target));
      return output;
    } catch (Exception exception) {
      throw new ToolException(
          "crs_transform_failed", "Cannot transform " + source + " to " + target, exception);
    }
  }

  public String identifier(CoordinateReferenceSystem crs) {
    if (crs == null) return null;
    try {
      String value = CRS.toSRS(crs, true);
      return value == null || value.isBlank() ? crs.getName().toString() : value;
    } catch (RuntimeException exception) {
      return crs.getName().toString();
    }
  }

  private static String normalize(String code) {
    String value = code == null || code.isBlank() ? "EPSG:4326" : code.strip();
    return value.matches("\\d+") ? "EPSG:" + value : value;
  }

  private static int epsg(String code) {
    String digits = normalize(code).replaceAll("(?i)^EPSG:", "");
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
