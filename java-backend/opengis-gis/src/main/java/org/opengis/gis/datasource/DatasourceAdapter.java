package org.opengis.gis.datasource;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Curated, allow-list-only GeoJSON datasource adapter. */
public final class DatasourceAdapter {
  private final ObjectMapper mapper;
  private final BoundedHttpClient http;
  private final GeoJsonTransfer transfer;
  private final List<Source> catalog;

  public DatasourceAdapter(ObjectMapper mapper) {
    this(mapper, new BoundedHttpClient(64 * 1024 * 1024), loadCatalog(mapper));
  }

  DatasourceAdapter(ObjectMapper mapper, BoundedHttpClient http, List<Source> catalog) {
    this.mapper = mapper;
    this.http = http;
    this.transfer = new GeoJsonTransfer(mapper);
    this.catalog = List.copyOf(catalog);
  }

  public JsonNode call(
      String command, JsonNode params, Path workspace, CancellationToken cancellation) {
    return switch (command) {
      case "list" -> list();
      case "get" -> mapper.valueToTree(find(requiredName(params)));
      case "fetch" -> fetch(params, workspace, cancellation);
      default ->
          throw new ToolException(
              "datasource_unknown_command", "Unknown datasource command: " + command);
    };
  }

  private JsonNode list() {
    ObjectNode result = mapper.createObjectNode();
    result.put("count", catalog.size());
    result.set("sources", mapper.valueToTree(catalog));
    return result;
  }

  private JsonNode fetch(JsonNode params, Path workspace, CancellationToken cancellation) {
    Source source = find(requiredName(params));
    byte[] bytes = http.get(source.url(), Duration.ofSeconds(30), cancellation);
    ObjectNode geojson = transfer.parse(bytes);
    String output = params.path("output_path").asText(params.path("save_path").asText(""));
    return transfer.finish(
        workspace,
        output,
        "data/" + source.id() + ".geojson",
        params.path("return_geojson").asBoolean(false),
        geojson);
  }

  private Source find(String name) {
    String query = name.toLowerCase(java.util.Locale.ROOT);
    return catalog.stream()
        .filter(source -> source.name().equalsIgnoreCase(name))
        .findFirst()
        .orElseGet(
            () ->
                catalog.stream()
                    .filter(
                        source -> source.name().toLowerCase(java.util.Locale.ROOT).contains(query))
                    .findFirst()
                    .orElseThrow(
                        () ->
                            new ToolException(
                                "datasource_not_found", "Datasource not found: " + name)));
  }

  private static String requiredName(JsonNode params) {
    String name = params.path("name").asText("");
    if (name.isBlank())
      throw new ToolException("datasource_name_required", "Datasource name is required");
    return name;
  }

  private static List<Source> loadCatalog(ObjectMapper mapper) {
    try (InputStream input =
        DatasourceAdapter.class.getResourceAsStream("/org/opengis/gis/datasource/catalog.json")) {
      if (input == null) throw new IllegalStateException("GIS datasource catalog is missing");
      ArrayNode values = (ArrayNode) mapper.readTree(input);
      List<Source> sources = new ArrayList<>();
      for (JsonNode value : values) {
        URI url = URI.create(value.path("url").asText());
        if (!"https".equalsIgnoreCase(url.getScheme())) {
          throw new IllegalStateException("Datasource catalog URL must use HTTPS");
        }
        sources.add(
            new Source(
                value.path("id").asText(),
                value.path("name").asText(),
                value.path("description").asText(),
                url));
      }
      return List.copyOf(sources);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot load GIS datasource catalog", exception);
    }
  }

  public record Source(String id, String name, String description, URI url) {}
}
