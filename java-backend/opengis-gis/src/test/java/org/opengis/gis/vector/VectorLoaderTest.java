package org.opengis.gis.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.opengis.gis.crs.CrsService;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.ObjectMapper;

class VectorLoaderTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final VectorLoader loader = new VectorLoader(mapper, new CrsService());

  @Test
  void loadsGeoJsonCsvAndKmlWithBoundsAndTruncation(@TempDir Path workspace) throws Exception {
    Path geojson = workspace.resolve("places.geojson");
    Files.writeString(
        geojson,
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4,31.2]},"properties":{"name":"A"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.5,31.3]},"properties":{"name":"B"}}
        ]}
        """,
        StandardCharsets.UTF_8);
    VectorLoadResult limited = loader.load(geojson, 1, new CancellationToken());
    assertThat(limited.truncated()).isTrue();
    assertThat(limited.metadata().featureCount()).isEqualTo(1);
    assertThat(limited.metadata().bounds()).containsExactly(121.4, 31.2, 121.4, 31.2);

    Path csv = workspace.resolve("points.csv");
    Files.writeString(csv, "name,longitude,latitude\nCafe,121.4,31.2\n", StandardCharsets.UTF_8);
    assertThat(loader.load(csv, 10, new CancellationToken()).geojson().path("features")).hasSize(1);

    Path kml = workspace.resolve("points.kml");
    Files.writeString(
        kml,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
          <name>Cafe</name><MultiGeometry>
            <Point><coordinates>121.4,31.2,0</coordinates></Point>
            <LineString><coordinates>121.4,31.2 121.5,31.3</coordinates></LineString>
          </MultiGeometry>
        </Placemark></Document></kml>
        """,
        StandardCharsets.UTF_8);
    VectorLoadResult kmlResult = loader.load(kml, 10, new CancellationToken());
    assertThat(kmlResult.metadata().formatName()).isEqualTo("KML");
    assertThat(kmlResult.geojson().path("features").get(0).path("geometry").path("type").asText())
        .isEqualTo("GeometryCollection");
  }

  @Test
  void loadsShapefileAndGeoPackageThroughGeoTools(@TempDir Path workspace) throws Exception {
    SimpleFeatureType schema = schema("places");
    Path shapefile = workspace.resolve("places.shp");
    DataStore store =
        new ShapefileDataStoreFactory()
            .createNewDataStore(
                Map.of(
                    "url",
                    shapefile.toUri().toURL(),
                    "create spatial index",
                    false,
                    "charset",
                    StandardCharsets.UTF_8));
    try {
      store.createSchema(schema);
      try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
          store.getFeatureWriterAppend(store.getTypeNames()[0], Transaction.AUTO_COMMIT)) {
        SimpleFeature feature = writer.next();
        feature.setDefaultGeometry(point());
        feature.setAttribute("name", "Cafe");
        writer.write();
      }
    } finally {
      store.dispose();
    }
    assertThat(loader.load(shapefile, 10, new CancellationToken()).metadata().featureCount())
        .isEqualTo(1);

    Path geopackage = workspace.resolve("places.gpkg");
    DefaultFeatureCollection collection = new DefaultFeatureCollection(null, schema);
    collection.add(SimpleFeatureBuilder.build(schema, new Object[] {point(), "Cafe"}, "place-1"));
    FeatureEntry entry = new FeatureEntry();
    entry.setTableName("places");
    entry.setIdentifier("places");
    try (GeoPackage gpkg = new GeoPackage(geopackage.toFile())) {
      gpkg.init();
      gpkg.add(entry, collection);
      SimpleFeatureType shopsSchema = schema("shops");
      DefaultFeatureCollection second = new DefaultFeatureCollection(null, shopsSchema);
      second.add(
          SimpleFeatureBuilder.build(shopsSchema, new Object[] {point(), "Bakery"}, "place-2"));
      FeatureEntry secondEntry = new FeatureEntry();
      secondEntry.setTableName("shops");
      secondEntry.setIdentifier("shops");
      gpkg.add(secondEntry, second);
    }
    VectorLoadResult loaded = loader.load(geopackage, 10, new CancellationToken());
    assertThat(loaded.metadata().featureCount()).isEqualTo(2);
    assertThat(loaded.geojson().path("features"))
        .allSatisfy(
            feature ->
                assertThat(feature.path("properties").path("_opengis_layer").asText())
                    .isNotBlank());
  }

  private static SimpleFeatureType schema(String name) throws Exception {
    SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
    builder.setName(name);
    builder.setCRS(CRS.decode("EPSG:4326", true));
    builder.add("geom", Point.class);
    builder.add("name", String.class);
    builder.setDefaultGeometry("geom");
    return builder.buildFeatureType();
  }

  private static Point point() {
    return new GeometryFactory().createPoint(new Coordinate(121.4, 31.2));
  }
}
