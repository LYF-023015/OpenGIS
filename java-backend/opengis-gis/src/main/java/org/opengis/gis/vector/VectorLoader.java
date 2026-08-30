package org.opengis.gis.vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.Query;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.data.geojson.GeoJSONWriter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.crs.GeoJsonCrsTransformer;
import org.opengis.gis.model.GisFormat;
import org.opengis.gis.model.GisMetadata;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.CancellationToken;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Bounded vector loader for the Phase 7 pure-Java format set. */
public final class VectorLoader {
  public static final int DEFAULT_MAX_FEATURES = 100_000;
  public static final long DEFAULT_MAX_INPUT_BYTES = 64L * 1024 * 1024;

  private final ObjectMapper mapper;
  private final CrsService crs;

  public VectorLoader(ObjectMapper mapper, CrsService crs) {
    this.mapper = mapper;
    this.crs = crs;
  }

  public VectorLoadResult load(Path path, int maxFeatures, CancellationToken cancellation) {
    GisFormat format =
        GisFormat.detect(path)
            .filter(value -> "vector".equals(value.kind()))
            .orElseThrow(() -> new ToolException("unsupported_gis_format", "Not a vector format"));
    guardSize(path);
    return switch (format) {
      case GEOJSON -> loadGeoJson(path, maxFeatures, cancellation);
      case CSV -> loadCsv(path, maxFeatures, cancellation);
      case KML -> loadKml(path, maxFeatures, cancellation);
      case SHAPEFILE, GEOPACKAGE -> loadDataStore(path, format, maxFeatures, cancellation);
      default -> throw new ToolException("unsupported_gis_format", "Unsupported vector format");
    };
  }

  public GisMetadata metadata(Path path, int maxFeatures, CancellationToken cancellation) {
    return load(path, maxFeatures, cancellation).metadata();
  }

  private VectorLoadResult loadGeoJson(Path path, int maxFeatures, CancellationToken cancellation) {
    try {
      cancellation.throwIfCancelled();
      JsonNode root = mapper.readTree(Files.readAllBytes(path));
      ObjectNode collection = normalizeCollection(root);
      ArrayNode features = (ArrayNode) collection.path("features");
      boolean truncated = trim(features, maxFeatures, cancellation);
      return new VectorLoadResult(
          collection, metadataFromGeoJson(path, GisFormat.GEOJSON, collection), truncated);
    } catch (IOException exception) {
      throw new ToolException("geojson_read_failed", "Cannot read GeoJSON", exception);
    }
  }

  private VectorLoadResult loadDataStore(
      Path path, GisFormat format, int maxFeatures, CancellationToken cancellation) {
    Map<String, Object> params = new LinkedHashMap<>();
    if (format == GisFormat.SHAPEFILE) {
      try {
        params.put("url", path.toUri().toURL());
      } catch (IOException exception) {
        throw new ToolException("vector_open_failed", "Invalid Shapefile path", exception);
      }
      params.put("charset", shapefileCharset(path));
      params.put("memory mapped buffer", false);
    } else {
      params.put("dbtype", "geopkg");
      params.put("database", path.toFile());
      params.put("read only", true);
    }
    DataStore store = null;
    try {
      store = DataStoreFinder.getDataStore(params);
      if (store == null) {
        throw new ToolException("vector_open_failed", "No GeoTools reader accepted " + path);
      }
      String[] names = store.getTypeNames();
      if (names.length == 0)
        throw new ToolException("vector_empty", "Vector dataset has no layers");
      if (format == GisFormat.GEOPACKAGE) {
        return loadGeoPackageTables(path, store, names, maxFeatures, cancellation);
      }
      SimpleFeatureSource source = store.getFeatureSource(names[0]);
      int count = source.getCount(Query.ALL);
      if (count > maxFeatures) {
        throw new ToolException(
            "vector_feature_limit", "Dataset has " + count + " features; limit is " + maxFeatures);
      }
      cancellation.throwIfCancelled();
      SimpleFeatureCollection collection = source.getFeatures();
      String encoded = GeoJSONWriter.toGeoJSON(collection);
      cancellation.throwIfCancelled();
      ObjectNode geojson = normalizeCollection(mapper.readTree(encoded));
      SimpleFeatureType schema = source.getSchema();
      List<GisMetadata.Field> fields =
          schema.getAttributeDescriptors().stream()
              .filter(attribute -> !attribute.equals(schema.getGeometryDescriptor()))
              .map(this::field)
              .toList();
      var bounds = source.getBounds();
      double[] envelope =
          bounds == null || bounds.isEmpty()
              ? null
              : new double[] {
                bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()
              };
      GisMetadata metadata =
          new GisMetadata(
              path,
              path.getFileName().toString(),
              "vector",
              format.displayName(),
              Files.size(path),
              crs.identifier(schema.getCoordinateReferenceSystem()),
              envelope,
              (long) geojson.path("features").size(),
              fields,
              null,
              null,
              null,
              null);
      return new VectorLoadResult(geojson, metadata, false);
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException(
          "vector_read_failed", "Cannot read " + format.displayName(), exception);
    } finally {
      if (store != null) store.dispose();
    }
  }

  private VectorLoadResult loadGeoPackageTables(
      Path path, DataStore store, String[] names, int maxFeatures, CancellationToken cancellation)
      throws Exception {
    int limit = Math.max(1, Math.min(maxFeatures, DEFAULT_MAX_FEATURES));
    ObjectNode combined = mapper.createObjectNode().put("type", "FeatureCollection");
    ArrayNode target = combined.putArray("features");
    GeoJsonCrsTransformer transformer = new GeoJsonCrsTransformer(mapper, crs);
    for (String name : names) {
      cancellation.throwIfCancelled();
      SimpleFeatureSource source = store.getFeatureSource(name);
      int count = source.getCount(Query.ALL);
      if (count > 0 && target.size() + count > limit) {
        throw new ToolException(
            "vector_feature_limit",
            "GeoPackage tables exceed the combined feature limit of " + limit);
      }
      ObjectNode layer =
          normalizeCollection(mapper.readTree(GeoJSONWriter.toGeoJSON(source.getFeatures())));
      String sourceCrs = crs.identifier(source.getSchema().getCoordinateReferenceSystem());
      if (sourceCrs != null && !sourceCrs.isBlank() && !"EPSG:4326".equalsIgnoreCase(sourceCrs)) {
        layer = transformer.transform(layer, sourceCrs, "EPSG:4326", cancellation);
      }
      for (JsonNode value : layer.path("features")) {
        cancellation.throwIfCancelled();
        if (target.size() >= limit) {
          throw new ToolException(
              "vector_feature_limit",
              "GeoPackage tables exceed the combined feature limit of " + limit);
        }
        ObjectNode feature = (ObjectNode) value.deepCopy();
        ObjectNode properties =
            feature.path("properties").isObject()
                ? (ObjectNode) feature.path("properties")
                : feature.putObject("properties");
        properties.put("_opengis_layer", name);
        if (sourceCrs != null && !sourceCrs.isBlank()) {
          properties.put("_opengis_source_crs", sourceCrs);
        }
        target.add(feature);
      }
    }
    return new VectorLoadResult(
        combined, metadataFromGeoJson(path, GisFormat.GEOPACKAGE, combined), false);
  }

  private VectorLoadResult loadCsv(Path path, int maxFeatures, CancellationToken cancellation) {
    try {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      if (lines.isEmpty()) throw new ToolException("empty_csv", "CSV file is empty");
      List<String> headers = parseCsv(lines.getFirst());
      int lat = find(headers, "lat", "latitude", "y");
      int lon = find(headers, "lng", "lon", "longitude", "x");
      if (lat < 0 || lon < 0) {
        throw new ToolException(
            "coordinate_columns_missing", "CSV needs latitude/longitude columns");
      }
      ObjectNode collection = mapper.createObjectNode().put("type", "FeatureCollection");
      ArrayNode features = collection.putArray("features");
      for (int index = 1; index < lines.size(); index++) {
        cancellation.throwIfCancelled();
        if (lines.get(index).isBlank()) continue;
        if (features.size() >= maxFeatures) {
          return new VectorLoadResult(
              collection, metadataFromGeoJson(path, GisFormat.CSV, collection), true);
        }
        List<String> values = parseCsv(lines.get(index));
        if (values.size() != headers.size()) {
          throw new ToolException("invalid_csv_row", "Invalid CSV row " + (index + 1));
        }
        ObjectNode feature = features.addObject().put("type", "Feature");
        feature
            .putObject("geometry")
            .put("type", "Point")
            .putArray("coordinates")
            .add(Double.parseDouble(values.get(lon)))
            .add(Double.parseDouble(values.get(lat)));
        ObjectNode properties = feature.putObject("properties");
        for (int column = 0; column < headers.size(); column++) {
          if (column != lat && column != lon)
            properties.put(headers.get(column), values.get(column));
        }
      }
      return new VectorLoadResult(
          collection, metadataFromGeoJson(path, GisFormat.CSV, collection), false);
    } catch (NumberFormatException exception) {
      throw new ToolException(
          "invalid_coordinate", "CSV contains a non-numeric coordinate", exception);
    } catch (IOException exception) {
      throw new ToolException("csv_read_failed", "Cannot read CSV", exception);
    }
  }

  private VectorLoadResult loadKml(Path path, int maxFeatures, CancellationToken cancellation) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var document = factory.newDocumentBuilder().parse(path.toFile());
      ObjectNode collection = mapper.createObjectNode().put("type", "FeatureCollection");
      ArrayNode features = collection.putArray("features");
      NodeList placemarks = document.getElementsByTagNameNS("*", "Placemark");
      boolean truncated = placemarks.getLength() > maxFeatures;
      for (int index = 0; index < Math.min(placemarks.getLength(), maxFeatures); index++) {
        cancellation.throwIfCancelled();
        Element placemark = (Element) placemarks.item(index);
        ObjectNode geometry = kmlGeometry(placemark);
        if (geometry == null) continue;
        ObjectNode feature = features.addObject().put("type", "Feature");
        feature.set("geometry", geometry);
        ObjectNode properties = feature.putObject("properties");
        properties.put("name", firstText(placemark, "name"));
        properties.put("description", firstText(placemark, "description"));
      }
      return new VectorLoadResult(
          collection, metadataFromGeoJson(path, GisFormat.KML, collection), truncated);
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("kml_read_failed", "Cannot read KML", exception);
    }
  }

  private ObjectNode kmlGeometry(Element placemark) {
    List<ObjectNode> geometries = new ArrayList<>();
    for (String type : List.of("Point", "LineString", "Polygon")) {
      NodeList nodes = placemark.getElementsByTagNameNS("*", type);
      for (int index = 0; index < nodes.getLength(); index++) {
        ObjectNode geometry = kmlPrimitive((Element) nodes.item(index), type);
        if (geometry != null) geometries.add(geometry);
      }
    }
    if (geometries.isEmpty()) return null;
    if (geometries.size() == 1) return geometries.getFirst();
    String type = geometries.getFirst().path("type").asString();
    if (geometries.stream().allMatch(value -> type.equals(value.path("type").asString()))) {
      ObjectNode multi = mapper.createObjectNode().put("type", "Multi" + type);
      ArrayNode coordinates = multi.putArray("coordinates");
      geometries.forEach(value -> coordinates.add(value.path("coordinates")));
      return multi;
    }
    ObjectNode collection = mapper.createObjectNode().put("type", "GeometryCollection");
    ArrayNode values = collection.putArray("geometries");
    geometries.forEach(values::add);
    return collection;
  }

  private ObjectNode kmlPrimitive(Element element, String type) {
    ObjectNode geometry = mapper.createObjectNode().put("type", type);
    ArrayNode target = geometry.putArray("coordinates");
    if ("Polygon".equals(type)) {
      NodeList outer = element.getElementsByTagNameNS("*", "outerBoundaryIs");
      if (outer.getLength() == 0) return null;
      if (!appendKmlRing(target, (Element) outer.item(0))) return null;
      NodeList inner = element.getElementsByTagNameNS("*", "innerBoundaryIs");
      for (int index = 0; index < inner.getLength(); index++) {
        appendKmlRing(target, (Element) inner.item(index));
      }
      return geometry;
    }
    List<double[]> coordinates = parseCoordinates(firstText(element, "coordinates"));
    if (coordinates.isEmpty()) return null;
    if ("Point".equals(type)) {
      target.add(coordinates.getFirst()[0]).add(coordinates.getFirst()[1]);
    } else {
      coordinates.forEach(value -> target.addArray().add(value[0]).add(value[1]));
    }
    return geometry;
  }

  private static boolean appendKmlRing(ArrayNode target, Element boundary) {
    List<double[]> coordinates = parseCoordinates(firstText(boundary, "coordinates"));
    if (coordinates.size() < 4) return false;
    ArrayNode ring = target.addArray();
    coordinates.forEach(value -> ring.addArray().add(value[0]).add(value[1]));
    return true;
  }

  private GisMetadata metadataFromGeoJson(Path path, GisFormat format, ObjectNode collection)
      throws IOException {
    ArrayNode features = (ArrayNode) collection.path("features");
    Bounds bounds = new Bounds();
    Map<String, String> fields = new LinkedHashMap<>();
    for (JsonNode feature : features) {
      visitGeometry(feature.path("geometry"), bounds);
      feature
          .path("properties")
          .properties()
          .forEach(entry -> fields.putIfAbsent(entry.getKey(), nodeType(entry.getValue())));
    }
    return new GisMetadata(
        path,
        path.getFileName().toString(),
        "vector",
        format.displayName(),
        Files.size(path),
        "EPSG:4326",
        bounds.value(),
        (long) features.size(),
        fields.entrySet().stream()
            .map(entry -> new GisMetadata.Field(entry.getKey(), entry.getValue()))
            .toList(),
        null,
        null,
        null,
        null);
  }

  private static ObjectNode normalizeCollection(JsonNode root) {
    if (root != null
        && root.isObject()
        && "FeatureCollection".equals(root.path("type").asString())
        && root.path("features").isArray()) {
      return (ObjectNode) root;
    }
    throw new ToolException("invalid_geojson", "Expected a GeoJSON FeatureCollection");
  }

  private static boolean trim(ArrayNode features, int maxFeatures, CancellationToken cancellation) {
    int limit = Math.max(1, Math.min(maxFeatures, DEFAULT_MAX_FEATURES));
    for (int index = 0; index < Math.min(features.size(), limit); index++) {
      cancellation.throwIfCancelled();
    }
    boolean truncated = features.size() > limit;
    while (features.size() > limit) features.remove(features.size() - 1);
    return truncated;
  }

  private static void guardSize(Path path) {
    try {
      long size = Files.size(path);
      if (size > DEFAULT_MAX_INPUT_BYTES) {
        throw new ToolException("gis_input_too_large", "GIS input exceeds 64 MiB safety limit");
      }
    } catch (IOException exception) {
      throw new ToolException("gis_file_unavailable", "Cannot inspect GIS input", exception);
    }
  }

  private static java.nio.charset.Charset shapefileCharset(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    Path cpg = path.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".cpg");
    if (!Files.isRegularFile(cpg)) return StandardCharsets.UTF_8;
    try {
      String value = Files.readString(cpg, StandardCharsets.US_ASCII).strip();
      return java.nio.charset.Charset.forName(value);
    } catch (Exception ignored) {
      return StandardCharsets.UTF_8;
    }
  }

  private GisMetadata.Field field(AttributeDescriptor descriptor) {
    return new GisMetadata.Field(
        descriptor.getLocalName(), descriptor.getType().getBinding().getSimpleName());
  }

  private static String firstText(Element parent, String localName) {
    NodeList values = parent.getElementsByTagNameNS("*", localName);
    return values.getLength() == 0 ? "" : values.item(0).getTextContent().strip();
  }

  private static List<double[]> parseCoordinates(String text) {
    List<double[]> values = new ArrayList<>();
    for (String tuple : text.strip().split("\\s+")) {
      String[] parts = tuple.split(",");
      if (parts.length >= 2)
        values.add(new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])});
    }
    return values;
  }

  private static List<String> parseCsv(String line) {
    List<String> values = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
          value.append('"');
          index++;
        } else quoted = !quoted;
      } else if (character == ',' && !quoted) {
        values.add(value.toString());
        value.setLength(0);
      } else value.append(character);
    }
    if (quoted) throw new ToolException("invalid_csv", "Unclosed quoted CSV field");
    values.add(value.toString());
    return values;
  }

  private static int find(List<String> headers, String... names) {
    for (int index = 0; index < headers.size(); index++) {
      String value = headers.get(index).strip().toLowerCase(Locale.ROOT);
      for (String name : names) if (name.equals(value)) return index;
    }
    return -1;
  }

  private static void visitCoordinates(JsonNode node, Bounds bounds) {
    if (!node.isArray() || node.isEmpty()) return;
    if (node.get(0).isNumber()) {
      if (node.size() >= 2) bounds.include(node.get(0).asDouble(), node.get(1).asDouble());
      return;
    }
    node.forEach(child -> visitCoordinates(child, bounds));
  }

  private static void visitGeometry(JsonNode geometry, Bounds bounds) {
    visitCoordinates(geometry.path("coordinates"), bounds);
    if (geometry.path("geometries").isArray()) {
      geometry.path("geometries").forEach(child -> visitGeometry(child, bounds));
    }
  }

  private static String nodeType(JsonNode node) {
    if (node.isIntegralNumber()) return "Long";
    if (node.isFloatingPointNumber()) return "Double";
    if (node.isBoolean()) return "Boolean";
    if (node.isArray()) return "Array";
    if (node.isObject()) return "Object";
    return "String";
  }

  private static final class Bounds {
    private double minX = Double.POSITIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;

    void include(double x, double y) {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }

    double[] value() {
      return Double.isInfinite(minX) ? null : new double[] {minX, minY, maxX, maxY};
    }
  }
}
