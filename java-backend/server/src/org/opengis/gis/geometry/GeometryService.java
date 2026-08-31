/** 文件职责：gis 后端领域：承载该领域的核心业务流程。 */
package org.opengis.gis.geometry;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.opengis.gis.error.GisException;

/** Bounded JTS geometry operations used by tools and operations. */
public final class GeometryService {
  private static final int MAX_POINTS = 1_000_000;
  private final WKTReader reader = new WKTReader();
  private final WKTWriter writer = new WKTWriter();

  public Result execute(String operation, String leftWkt, String rightWkt, double distance) {
    try {
      Geometry left = reader.read(leftWkt);
      guard(left);
      Geometry result =
          switch (operation) {
            case "buffer" -> left.buffer(distance);
            case "centroid" -> left.getCentroid();
            case "convex_hull" -> left.convexHull();
            case "intersection" -> left.intersection(requiredRight(rightWkt));
            case "union" -> left.union(requiredRight(rightWkt));
            case "difference" -> left.difference(requiredRight(rightWkt));
            default ->
                throw new GisException(
                    "unsupported_geometry_operation",
                    "Unsupported geometry operation: " + operation);
          };
      guard(result);
      return new Result(
          writer.write(result), result.getGeometryType(), result.getArea(), result.getLength());
    } catch (GisException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new GisException("invalid_geometry", "Cannot process WKT geometry", exception);
    }
  }

  private Geometry requiredRight(String value) throws Exception {
    if (value == null || value.isBlank()) {
      throw new GisException("right_geometry_required", "This operation requires right_wkt");
    }
    Geometry geometry = reader.read(value);
    guard(geometry);
    return geometry;
  }

  private static void guard(Geometry geometry) {
    if (geometry.getNumPoints() > MAX_POINTS) {
      throw new GisException("geometry_too_large", "Geometry exceeds " + MAX_POINTS + " points");
    }
  }

  public record Result(String wkt, String geometryType, double area, double length) {}
}
