package org.opengis.gis.crs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.opengis.gis.geometry.GeometryService;

class CrsAndGeometryTest {
  @Test
  void transformsLongitudeFirstAndRunsJtsOperations() {
    var point = new GeometryFactory().createPoint(new Coordinate(180, 0));
    var mercator = new CrsService().transform(point, "EPSG:4326", "EPSG:3857");
    assertThat(mercator.getCoordinate().x).isCloseTo(20_037_508.34, within(0.1));
    assertThat(mercator.getCoordinate().y).isCloseTo(0, within(0.1));

    GeometryService.Result result = new GeometryService().execute("buffer", "POINT (0 0)", "", 10);
    assertThat(result.geometryType()).isEqualTo("Polygon");
    assertThat(result.area()).isGreaterThan(300);
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
