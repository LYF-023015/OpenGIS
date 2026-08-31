/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.operation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import tools.jackson.databind.ObjectMapper;

/** Bounded output writers used by format_converter. */
public final class VectorWriters {
  private VectorWriters() {}

  public static void writeGeoJson(ObjectMapper mapper, GeoJsonFeatureSet dataset, Path output)
      throws IOException {
    Files.writeString(
        output,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataset.toGeoJson("", null))
            + "\n",
        StandardCharsets.UTF_8);
  }

  public static void writeCsv(GeoJsonFeatureSet dataset, Path output) throws IOException {
    Set<String> fields = new LinkedHashSet<>();
    dataset.features().forEach(feature -> fields.addAll(feature.properties().keySet()));
    StringBuilder value = new StringBuilder();
    value.append("geometry_wkt");
    fields.forEach(field -> value.append(',').append(csv(field)));
    value.append('\n');
    for (var feature : dataset.features()) {
      value.append(csv(feature.geometry().toText()));
      fields.forEach(field -> value.append(',').append(csv(feature.properties().get(field))));
      value.append('\n');
    }
    Files.writeString(output, value, StandardCharsets.UTF_8);
  }

  public static void writeKml(GeoJsonFeatureSet dataset, Path output) throws IOException {
    StringBuilder value = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    value.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>");
    int index = 0;
    for (var feature : dataset.features()) {
      value
          .append("<Placemark><name>")
          .append(xml(feature.properties().getOrDefault("name", "feature-" + index++)))
          .append("</name>");
      appendGeometry(value, feature.geometry());
      value.append("</Placemark>");
    }
    value.append("</Document></kml>\n");
    Files.writeString(output, value, StandardCharsets.UTF_8);
  }

  public static void writeShapefile(GeoJsonFeatureSet dataset, Path output) throws IOException {
    var collection = features(dataset, true);
    ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
    DataStore store =
        factory.createNewDataStore(
            Map.of(
                "url",
                output.toUri().toURL(),
                "create spatial index",
                true,
                "charset",
                StandardCharsets.UTF_8));
    try {
      store.createSchema(collection.getSchema());
      if (store instanceof ShapefileDataStore shapefile)
        shapefile.setCharset(StandardCharsets.UTF_8);
      SimpleFeatureStore target =
          (SimpleFeatureStore) store.getFeatureSource(store.getTypeNames()[0]);
      target.addFeatures(collection);
    } finally {
      store.dispose();
    }
    Files.writeString(
        output.resolveSibling(stem(output) + ".cpg"), "UTF-8\n", StandardCharsets.UTF_8);
  }

  public static void writeGeoPackage(GeoJsonFeatureSet dataset, Path output) throws IOException {
    Files.deleteIfExists(output);
    var collection = features(dataset, false);
    try (GeoPackage geopackage = new GeoPackage(output.toFile())) {
      geopackage.init();
      FeatureEntry entry = new FeatureEntry();
      entry.setTableName(stem(output));
      entry.setDescription("OpenGIS format_converter output");
      geopackage.add(entry, collection);
    }
  }

  private static ListFeatureCollection features(GeoJsonFeatureSet dataset, boolean shapefile) {
    if (dataset.features().isEmpty())
      throw new IllegalArgumentException("Cannot write an empty vector dataset");
    Class<?> geometryType = dataset.features().getFirst().geometry().getClass();
    if (dataset.features().stream()
        .anyMatch(value -> !geometryType.equals(value.geometry().getClass()))) {
      throw new IllegalArgumentException("Shapefile/GeoPackage output requires one geometry type");
    }
    Set<String> rawFields = new LinkedHashSet<>();
    dataset.features().forEach(feature -> rawFields.addAll(feature.properties().keySet()));
    List<String> fields =
        rawFields.stream().map(value -> field(value, shapefile)).distinct().toList();
    SimpleFeatureTypeBuilder type = new SimpleFeatureTypeBuilder();
    type.setName("features");
    type.setSRS(dataset.crs());
    type.add("the_geom", geometryType);
    type.setDefaultGeometry("the_geom");
    fields.forEach(value -> type.length(254).add(value, String.class));
    var schema = type.buildFeatureType();
    ListFeatureCollection collection = new ListFeatureCollection(schema);
    int index = 0;
    for (var source : dataset.features()) {
      SimpleFeatureBuilder feature = new SimpleFeatureBuilder(schema);
      feature.set("the_geom", source.geometry());
      int fieldIndex = 0;
      for (String raw : rawFields) {
        Object value = source.properties().get(raw);
        feature.set(fields.get(fieldIndex++), value == null ? null : String.valueOf(value));
      }
      collection.add(
          feature.buildFeature(source.id().isBlank() ? String.valueOf(index++) : source.id()));
    }
    return collection;
  }

  private static String field(String value, boolean shapefile) {
    String safe = value.replaceAll("[^\\p{L}\\p{N}_]", "_");
    if (safe.isBlank()) safe = "field";
    return shapefile && safe.length() > 10 ? safe.substring(0, 10) : safe;
  }

  private static void appendGeometry(StringBuilder value, Geometry geometry) {
    if (geometry instanceof Point point) {
      value
          .append("<Point><coordinates>")
          .append(coordinates(new Coordinate[] {point.getCoordinate()}))
          .append("</coordinates></Point>");
    } else if (geometry instanceof LineString line) {
      value
          .append("<LineString><coordinates>")
          .append(coordinates(line.getCoordinates()))
          .append("</coordinates></LineString>");
    } else if (geometry instanceof Polygon polygon) {
      value
          .append("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
          .append(coordinates(polygon.getExteriorRing().getCoordinates()))
          .append("</coordinates></LinearRing></outerBoundaryIs>");
      for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
        value
            .append("<innerBoundaryIs><LinearRing><coordinates>")
            .append(coordinates(polygon.getInteriorRingN(index).getCoordinates()))
            .append("</coordinates></LinearRing></innerBoundaryIs>");
      }
      value.append("</Polygon>");
    } else if (geometry instanceof GeometryCollection collection) {
      value.append("<MultiGeometry>");
      for (int index = 0; index < collection.getNumGeometries(); index++)
        appendGeometry(value, collection.getGeometryN(index));
      value.append("</MultiGeometry>");
    } else {
      throw new IllegalArgumentException("Unsupported KML geometry: " + geometry.getGeometryType());
    }
  }

  private static String coordinates(Coordinate[] values) {
    List<String> result = new ArrayList<>();
    for (Coordinate coordinate : values) result.add(coordinate.x + "," + coordinate.y);
    return String.join(" ", result);
  }

  private static String csv(Object value) {
    String text = value == null ? "" : String.valueOf(value);
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  private static String xml(Object value) {
    return String.valueOf(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String stem(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }
}
