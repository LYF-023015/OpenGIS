/** 文件职责：plugins 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.plugins.gis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.crs.GeoJsonCrsTransformer;
import org.opengis.gis.datasource.DatasourceAdapter;
import org.opengis.gis.error.GisException;
import org.opengis.gis.geometry.GeometryService;
import org.opengis.gis.io.WorkspaceGisPaths;
import org.opengis.gis.model.GisFormat;
import org.opengis.gis.osm.OsmAdapter;
import org.opengis.gis.qgis.QgisClient;
import org.opengis.gis.raster.RasterContracts;
import org.opengis.gis.raster.RasterRegistration;
import org.opengis.gis.raster.RasterService;
import org.opengis.gis.raster.RasterStyle;
import org.opengis.gis.vector.VectorLoadResult;
import org.opengis.gis.vector.VectorLoader;
import org.opengis.core.persistence.JsonTypeReferences;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Tool adapters assembled around reusable GIS domain services. */
final class GisToolCatalog {
  private GisToolCatalog() {}

  static List<OpenGisTool> create(ObjectMapper mapper, RasterService rasters) {
    CrsService crs = new CrsService();
    VectorLoader vectors = new VectorLoader(mapper, crs);
    GeometryService geometry = new GeometryService();
    GeoJsonCrsTransformer transformer = new GeoJsonCrsTransformer(mapper, crs);
    OsmAdapter osm = new OsmAdapter(mapper);
    DatasourceAdapter datasources = new DatasourceAdapter(mapper);
    String host = System.getenv().getOrDefault("QGIS_MCP_HOST", "127.0.0.1");
    int port = parsePort(System.getenv().getOrDefault("QGIS_MCP_PORT", "9876"));
    QgisClient qgis = new QgisClient(mapper, host, port, Duration.ofSeconds(5));
    List<OpenGisTool> tools = new ArrayList<>();
    tools.add(tool(capabilityDefinition(mapper), (args, context) -> capabilities(mapper)));
    tools.add(
        tool(
            fileInfoDefinition(mapper),
            (args, context) -> fileInfo(args, context, vectors, rasters, mapper)));
    tools.add(
        tool(
            loadVectorDefinition(mapper),
            (args, context) -> loadVector(args, context, vectors, mapper)));
    tools.add(
        tool(
            transformDefinition(mapper),
            (args, context) -> transform(args, context, vectors, transformer, mapper)));
    tools.add(
        tool(
            geometryDefinition(mapper),
            (args, context) ->
                mapper.valueToTree(
                    geometry.execute(
                        args.path("operation").asString(), args.path("left_wkt").asString(),
                        args.path("right_wkt").asString(""), args.path("distance").asDouble(0)))));
    tools.add(
        tool(
            registerRasterDefinition(mapper),
            (args, context) -> registerRaster(args, context, rasters, mapper)));
    tools.add(
        tool(
            adapterDefinition(mapper, "osm_call", "OSM Call", ToolRisk.NETWORK),
            (args, context) ->
                osm.call(
                    args.path("command").asString(),
                    adapterParams(args, mapper),
                    context.workspace(),
                    context.cancellation())));
    tools.add(
        tool(
            adapterDefinition(mapper, "datasource_call", "Datasource Call", ToolRisk.NETWORK),
            (args, context) ->
                datasources.call(
                    args.path("command").asString(),
                    adapterParams(args, mapper),
                    context.workspace(),
                    context.cancellation())));
    tools.add(
        tool(
            adapterDefinition(mapper, "qgis_call", "QGIS Call", ToolRisk.NETWORK),
            (args, context) ->
                qgis.call(
                    args.path("command").asString(),
                    adapterParams(args, mapper),
                    context.cancellation())));
    return List.copyOf(tools);
  }

  private static JsonNode capabilities(ObjectMapper mapper) {
    ObjectNode result = mapper.createObjectNode();
    result.put("runtime", "java");
    result.put("geotools_version", "35.0");
    result.set("formats", mapper.valueToTree(List.of(GisFormat.values())));
    result.set("vector", mapper.valueToTree(List.of("metadata", "load", "crs", "geometry")));
    result.set(
        "raster", mapper.valueToTree(List.of("metadata", "style", "xyz_tiles", "revision_cache")));
    result.set("adapters", mapper.valueToTree(List.of("osm", "qgis", "datasource")));
    return result;
  }

  private static JsonNode fileInfo(
      JsonNode args,
      ToolExecutionContext context,
      VectorLoader vectors,
      RasterService rasters,
      ObjectMapper mapper) {
    Path path = WorkspaceGisPaths.input(context.workspace(), args.path("path").asString());
    GisFormat format =
        GisFormat.detect(path)
            .orElseThrow(
                () -> new ToolException("unsupported_gis_format", "Unsupported GIS format"));
    if ("vector".equals(format.kind())) {
      return mapper.valueToTree(
          vectors.metadata(path, args.path("max_features").asInt(100_000), context.cancellation()));
    }
    if ("raster".equals(format.kind())) {
      return mapper.valueToTree(rasters.inspect(path, context.cancellation()));
    }
    throw new ToolException("unsupported_gis_format", "Unsupported GIS format");
  }

  private static JsonNode loadVector(
      JsonNode args, ToolExecutionContext context, VectorLoader vectors, ObjectMapper mapper) {
    Path path = WorkspaceGisPaths.input(context.workspace(), args.path("path").asString());
    VectorLoadResult result =
        vectors.load(path, args.path("max_features").asInt(100_000), context.cancellation());
    ObjectNode output = mapper.createObjectNode();
    output.set("geojson", result.geojson());
    output.set("metadata", mapper.valueToTree(result.metadata()));
    output.put("truncated", result.truncated());
    return output;
  }

  private static JsonNode transform(
      JsonNode args,
      ToolExecutionContext context,
      VectorLoader vectors,
      GeoJsonCrsTransformer transformer,
      ObjectMapper mapper) {
    Path input = WorkspaceGisPaths.input(context.workspace(), args.path("input_path").asString());
    VectorLoadResult loaded =
        vectors.load(input, args.path("max_features").asInt(100_000), context.cancellation());
    String source = args.path("source_crs").asString(loaded.metadata().crs());
    String target = args.path("target_crs").asString("EPSG:4326");
    ObjectNode geojson =
        transformer.transform(loaded.geojson(), source, target, context.cancellation());
    String outputValue = args.path("output_path").asString("");
    if (outputValue.isBlank()) {
      outputValue =
          "data/"
              + stripExtension(input.getFileName().toString())
              + "_"
              + target.replace(':', '_')
              + ".geojson";
    }
    Path output = WorkspaceGisPaths.output(context.workspace(), outputValue, outputValue);
    try {
      Files.writeString(
          output,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(geojson) + "\n",
          StandardCharsets.UTF_8);
    } catch (Exception exception) {
      throw new ToolException(
          "geojson_write_failed", "Cannot write transformed GeoJSON", exception);
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("output_path", output.toString());
    result.put("feature_count", geojson.path("features").size());
    result.put("source_crs", source);
    result.put("target_crs", target);
    return result;
  }

  private static JsonNode registerRaster(
      JsonNode args, ToolExecutionContext context, RasterService rasters, ObjectMapper mapper) {
    Path path = WorkspaceGisPaths.input(context.workspace(), args.path("path").asString());
    Map<String, Object> style =
        args.path("style").isObject()
            ? mapper.convertValue(args.path("style"), JsonTypeReferences.STRING_OBJECT_MAP)
            : Map.of();
    RasterRegistration registration =
        rasters.register(
            path, RasterStyle.merge(RasterStyle.defaults(), style), context.cancellation());
    ObjectNode result = mapper.valueToTree(RasterContracts.registration(registration));
    result.put(
        "tile_url",
        "/api/rasters/"
            + registration.rasterId()
            + "/tiles/{z}/{x}/{y}.png?rev="
            + registration.styleRevision());
    return result;
  }

  private static JsonNode adapterParams(JsonNode args, ObjectMapper mapper) {
    JsonNode value = args.path("params");
    if (value.isObject()) return value;
    if (!value.isString() || value.asString().isBlank()) return mapper.createObjectNode();
    try {
      JsonNode parsed = mapper.readTree(value.asString());
      if (!parsed.isObject())
        throw new ToolException("invalid_adapter_params", "params must be a JSON object");
      return parsed;
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("invalid_adapter_params", "Cannot parse adapter params", exception);
    }
  }

  private static ToolDefinition capabilityDefinition(ObjectMapper mapper) {
    return definition(
        mapper,
        "gis_capabilities",
        "GIS Capabilities",
        "Show the Phase 7 Java GIS capability matrix.",
        ToolRisk.READ,
        Map.of());
  }

  private static ToolDefinition fileInfoDefinition(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("path", string(mapper));
    fields.put("max_features", integer(mapper, 1, 100_000));
    return definition(
        mapper,
        "gis_file_info",
        "GIS File Info",
        "Inspect vector or GeoTIFF metadata without Python.",
        ToolRisk.READ,
        fields,
        "path");
  }

  private static ToolDefinition loadVectorDefinition(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("path", string(mapper));
    fields.put("max_features", integer(mapper, 1, 100_000));
    return definition(
        mapper,
        "load_gis_vector",
        "Load GIS Vector",
        "Load bounded GeoJSON, CSV, Shapefile, GeoPackage or KML as GeoJSON.",
        ToolRisk.READ,
        fields,
        "path");
  }

  private static ToolDefinition transformDefinition(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("input_path", string(mapper));
    fields.put("output_path", optionalString(mapper));
    fields.put("source_crs", optionalString(mapper));
    fields.put("target_crs", string(mapper));
    fields.put("max_features", integer(mapper, 1, 100_000));
    return definition(
        mapper,
        "transform_geojson_crs",
        "Transform GeoJSON CRS",
        "Transform a vector dataset to another CRS and save GeoJSON.",
        ToolRisk.WRITE,
        fields,
        "input_path",
        "target_crs");
  }

  private static ToolDefinition geometryDefinition(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put(
        "operation",
        enumString(
            mapper, "buffer", "centroid", "convex_hull", "intersection", "union", "difference"));
    fields.put("left_wkt", string(mapper));
    fields.put("right_wkt", optionalString(mapper));
    fields.put("distance", number(mapper, -100_000_000, 100_000_000));
    return definition(
        mapper,
        "geometry_operation",
        "Geometry Operation",
        "Execute a bounded JTS geometry operation on WKT.",
        ToolRisk.PROCESS,
        fields,
        "operation",
        "left_wkt");
  }

  private static ToolDefinition registerRasterDefinition(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("path", string(mapper));
    fields.put("style", openObject(mapper));
    return definition(
        mapper,
        "register_raster_tiles",
        "Register Raster Tiles",
        "Register a GeoTIFF for Java XYZ tile rendering.",
        ToolRisk.PROCESS,
        fields,
        "path");
  }

  private static ToolDefinition adapterDefinition(
      ObjectMapper mapper, String name, String title, ToolRisk risk) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("command", string(mapper));
    fields.put("params", optionalString(mapper));
    return definition(
        mapper,
        name,
        title,
        "Run the Java " + title + " adapter with JSON-string params.",
        risk,
        fields,
        "command");
  }

  private static ToolDefinition definition(
      ObjectMapper mapper,
      String name,
      String title,
      String description,
      ToolRisk risk,
      Map<String, JsonNode> fields,
      String... required) {
    ObjectNode schema =
        mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
    ObjectNode properties = schema.putObject("properties");
    fields.forEach(properties::set);
    var requiredFields = schema.putArray("required");
    for (String field : required) requiredFields.add(field);
    return new ToolDefinition(
        name, title, description, "data", "gis", "2.0.0", risk, schema, List.of("gis", "java"));
  }

  private static ObjectNode string(ObjectMapper mapper) {
    return mapper.createObjectNode().put("type", "string").put("minLength", 1);
  }

  private static ObjectNode optionalString(ObjectMapper mapper) {
    return mapper.createObjectNode().put("type", "string");
  }

  private static ObjectNode integer(ObjectMapper mapper, int min, int max) {
    return mapper.createObjectNode().put("type", "integer").put("minimum", min).put("maximum", max);
  }

  private static ObjectNode number(ObjectMapper mapper, double min, double max) {
    return mapper.createObjectNode().put("type", "number").put("minimum", min).put("maximum", max);
  }

  private static ObjectNode openObject(ObjectMapper mapper) {
    return mapper
        .createObjectNode()
        .put("type", "object")
        .put("additionalProperties", true)
        .set("properties", mapper.createObjectNode());
  }

  private static ObjectNode enumString(ObjectMapper mapper, String... values) {
    ObjectNode schema = string(mapper);
    var allowed = schema.putArray("enum");
    for (String value : values) allowed.add(value);
    return schema;
  }

  private static OpenGisTool tool(ToolDefinition definition, Executor executor) {
    return new OpenGisTool() {
      @Override
      public ToolDefinition definition() {
        return definition;
      }

      @Override
      public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
        try {
          return executor.execute(arguments, context);
        } catch (GisException exception) {
          throw new ToolException(exception.code(), exception.getMessage(), exception);
        }
      }
    };
  }

  private static int parsePort(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return 9876;
    }
  }

  private static String stripExtension(String value) {
    int dot = value.lastIndexOf('.');
    return dot > 0 ? value.substring(0, dot) : value;
  }

  @FunctionalInterface
  private interface Executor {
    JsonNode execute(JsonNode arguments, ToolExecutionContext context);
  }
}
