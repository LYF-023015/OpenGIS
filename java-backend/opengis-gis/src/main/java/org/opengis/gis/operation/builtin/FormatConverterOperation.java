package org.opengis.gis.operation.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.opengis.gis.io.WorkspaceGisPaths;
import org.opengis.gis.operation.BuiltinOperation;
import org.opengis.gis.operation.GeoJsonFeatureSet;
import org.opengis.gis.operation.OperationManifests;
import org.opengis.gis.operation.VectorWriters;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic multi-format vector conversion with explicit encoding and WKT CSV output. */
public final class FormatConverterOperation implements BuiltinOperation {
  private final ObjectMapper mapper;
  private final ObjectNode manifest;

  public FormatConverterOperation(ObjectMapper mapper) {
    this.mapper = mapper;
    this.manifest =
        OperationManifests.builtin(
            mapper,
            id(),
            "格式转换",
            "在 GeoJSON、CSV/WKT、Shapefile、GeoPackage 和 KML 之间转换矢量数据。",
            getClass().getName(),
            Map.of(
                "input_path", OperationManifests.string("Workspace 内输入路径"),
                "output_path", OperationManifests.string("Workspace 内输出路径"),
                "output_format",
                    Map.of(
                        "type", "string", "enum", List.of("geojson", "csv", "shp", "gpkg", "kml"))),
            List.of("input_path", "output_path"));
  }

  @Override
  public String id() {
    return "format_converter";
  }

  @Override
  public JsonNode manifest() {
    return manifest.deepCopy();
  }

  @Override
  public JsonNode run(Path workspace, JsonNode parameters, CancellationToken cancellation) {
    Path input = WorkspaceGisPaths.input(workspace, parameters.path("input_path").asText());
    String requested = parameters.path("output_format").asText("").toLowerCase(Locale.ROOT);
    String outputValue = parameters.path("output_path").asText();
    String format = requested.isBlank() ? extension(outputValue) : requested;
    Path output = WorkspaceGisPaths.output(workspace, outputValue, "converted/output." + format);
    GeoJsonFeatureSet dataset = GeoJsonFeatureSet.load(mapper, input, cancellation);
    cancellation.throwIfCancelled();
    try {
      switch (format) {
        case "geojson", "json" -> VectorWriters.writeGeoJson(mapper, dataset, output);
        case "csv" -> VectorWriters.writeCsv(dataset, output);
        case "kml" -> VectorWriters.writeKml(dataset, output);
        case "shp", "shapefile" -> VectorWriters.writeShapefile(dataset, output);
        case "gpkg", "geopackage" -> VectorWriters.writeGeoPackage(dataset, output);
        default -> throw new IllegalArgumentException("Unsupported output format: " + format);
      }
      ObjectNode result = mapper.createObjectNode();
      result.put("success", true);
      result.put("input_format", extension(input.getFileName().toString()));
      result.put("output_format", format);
      result.put("feature_count", dataset.features().size());
      result.put("crs", dataset.crs());
      result.put(
          "output_path",
          workspace.toAbsolutePath().normalize().relativize(output).toString().replace('\\', '/'));
      result.put("file_size_bytes", Files.size(output));
      return result;
    } catch (IOException exception) {
      throw new IllegalStateException("Format conversion failed", exception);
    }
  }

  private static String extension(String value) {
    int dot = value.lastIndexOf('.');
    return dot < 0 ? "geojson" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
