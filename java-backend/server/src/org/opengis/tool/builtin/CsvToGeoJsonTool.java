/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.support.FunctionalTool;
import org.opengis.tool.support.ToolSchemas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Phase 4 GIS edge tool: UTF-8 CSV point data to RFC 7946 GeoJSON. */
final class CsvToGeoJsonTool extends FunctionalTool {
  CsvToGeoJsonTool(ObjectMapper mapper) {
    super(definition(mapper), (arguments, context) -> convert(arguments, context, mapper));
  }

  private static ToolDefinition definition(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("input_path", ToolSchemas.string(mapper));
    fields.put("output_path", ToolSchemas.optionalString(mapper));
    fields.put("lat_column", ToolSchemas.optionalString(mapper));
    fields.put("lng_column", ToolSchemas.optionalString(mapper));
    fields.put("delimiter", ToolSchemas.optionalString(mapper));
    return new ToolDefinition(
        "csv_to_geojson",
        "CSV to GeoJSON",
        "Convert a UTF-8 point CSV to WGS84 GeoJSON; full GIS I/O remains in Phase 7.",
        "data",
        "core",
        "1.0.0",
        ToolRisk.WRITE,
        ToolSchemas.object(mapper, fields, "input_path"),
        List.of("gis", "geojson", "csv"));
  }

  private static JsonNode convert(
      JsonNode arguments, ToolExecutionContext context, ObjectMapper mapper) {
    Path input = WorkspacePaths.resolve(context, arguments.path("input_path").asString());
    String rawOutput = arguments.path("output_path").asString("");
    Path output =
        rawOutput.isBlank()
            ? input.resolveSibling(stripExtension(input.getFileName().toString()) + ".geojson")
            : WorkspacePaths.resolve(context, rawOutput);
    char delimiter = arguments.path("delimiter").asString(",").charAt(0);
    try {
      List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
      if (lines.isEmpty()) {
        throw new ToolException("empty_csv", "CSV file is empty");
      }
      List<String> headers = parseRow(lines.getFirst(), delimiter);
      int latitude =
          findColumn(headers, arguments.path("lat_column").asString(""), "lat", "latitude", "y");
      int longitude =
          findColumn(
              headers, arguments.path("lng_column").asString(""), "lng", "lon", "longitude", "x");
      if (latitude < 0 || longitude < 0) {
        throw new ToolException(
            "coordinate_columns_missing", "Latitude/longitude columns were not found");
      }
      ObjectNode collection = mapper.createObjectNode().put("type", "FeatureCollection");
      ArrayNode features = collection.putArray("features");
      for (int rowIndex = 1; rowIndex < lines.size(); rowIndex++) {
        context.cancellation().throwIfCancelled();
        if (lines.get(rowIndex).isBlank()) {
          continue;
        }
        List<String> row = parseRow(lines.get(rowIndex), delimiter);
        if (row.size() < headers.size()) {
          throw new ToolException(
              "invalid_csv_row", "CSV row " + (rowIndex + 1) + " has too few columns");
        }
        double lat = Double.parseDouble(row.get(latitude));
        double lng = Double.parseDouble(row.get(longitude));
        ObjectNode feature = features.addObject().put("type", "Feature");
        ArrayNode coordinates =
            feature.putObject("geometry").put("type", "Point").putArray("coordinates");
        coordinates.add(lng).add(lat);
        ObjectNode properties = feature.putObject("properties");
        for (int index = 0; index < headers.size(); index++) {
          if (index != latitude && index != longitude) {
            properties.put(headers.get(index), row.get(index));
          }
        }
      }
      Files.createDirectories(output.getParent());
      Files.writeString(
          output,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(collection) + "\n",
          StandardCharsets.UTF_8);
      ObjectNode result = mapper.createObjectNode();
      result.put("output_path", output.toString());
      result.put("feature_count", features.size());
      result.set("geojson", collection);
      return result;
    } catch (NumberFormatException exception) {
      throw new ToolException(
          "invalid_coordinate", "CSV contains a non-numeric coordinate", exception);
    } catch (IOException exception) {
      throw new ToolException("csv_conversion_failed", "Cannot convert CSV", exception);
    }
  }

  private static List<String> parseRow(String line, char delimiter) {
    List<String> values = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
          value.append('"');
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (character == delimiter && !quoted) {
        values.add(value.toString());
        value.setLength(0);
      } else {
        value.append(character);
      }
    }
    if (quoted) {
      throw new ToolException("invalid_csv", "Unclosed quoted CSV field");
    }
    values.add(value.toString());
    return values;
  }

  private static int findColumn(List<String> headers, String explicit, String... candidates) {
    if (!explicit.isBlank()) {
      return headers.indexOf(explicit);
    }
    for (int index = 0; index < headers.size(); index++) {
      String header = headers.get(index).strip().toLowerCase(Locale.ROOT);
      for (String candidate : candidates) {
        if (candidate.equals(header)) {
          return index;
        }
      }
    }
    return -1;
  }

  private static String stripExtension(String value) {
    int dot = value.lastIndexOf('.');
    return dot > 0 ? value.substring(0, dot) : value;
  }
}
