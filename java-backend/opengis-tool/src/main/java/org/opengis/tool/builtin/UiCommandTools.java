package org.opengis.tool.builtin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Thin adapters: map/layout semantics stay in the Renderer and share canonical RPC names. */
final class UiCommandTools {
  private static final Map<String, String> METHODS = createMethods();

  private UiCommandTools() {}

  static List<OpenGisTool> create(ObjectMapper mapper) {
    List<OpenGisTool> tools = new ArrayList<>();
    METHODS.forEach(
        (name, method) ->
            tools.add(
                new FunctionalTool(
                    definition(mapper, name, method),
                    (arguments, context) -> {
                      context.cancellation().throwIfCancelled();
                      if ("set_basemap".equals(name)) {
                        throw new ToolException(
                            "basemap_user_controlled",
                            "Agent-initiated basemap switching is disabled; ask the user to use the UI");
                      }
                      String resolvedMethod = methodFor(name, arguments);
                      JsonNode payload = payloadFor(name, arguments, mapper, context);
                      try {
                        return context
                            .uiRpc()
                            .request(resolvedMethod, payload, Duration.ofSeconds(30))
                            .toCompletableFuture()
                            .get(35, TimeUnit.SECONDS);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ToolException(
                            "tool_cancelled", "UI request interrupted", exception);
                      } catch (ExecutionException | TimeoutException exception) {
                        throw new ToolException(
                            "ui_request_failed",
                            "Renderer command failed: " + resolvedMethod,
                            exception);
                      }
                    })));
    return List.copyOf(tools);
  }

  private static ToolDefinition definition(ObjectMapper mapper, String name, String method) {
    return new ToolDefinition(
        name,
        title(name),
        "Renderer-backed command " + method + ".",
        method.contains("layout") ? "layout" : "visualization",
        "core",
        "1.0.0",
        mutating(name) ? ToolRisk.UI : ToolRisk.READ,
        schemaFor(mapper, name),
        List.of("renderer"));
  }

  private static JsonNode schemaFor(ObjectMapper mapper, String name) {
    List<String> required =
        switch (name) {
          case "remove_layer",
              "get_layer",
              "query_features",
              "zoom_to_layer",
              "update_layer_style",
              "set_layer_visual_variables",
              "set_layer_filter",
              "set_layer_label",
              "highlight_features",
              "update_legend_spec",
              "get_raster_info",
              "set_raster_style" ->
              List.of("layer_id");
          case "set_graduated_style", "set_categorized_style" -> List.of("layer_id", "field");
          case "set_extrusion_style" -> List.of("layer_id", "height_field");
          case "set_layer_order" -> List.of("layer_ids");
          case "set_basemap" -> List.of("basemap_id");
          case "set_basemap_visibility" -> List.of("visible");
          case "layout_add_element" -> List.of("type");
          case "layout_update_frame",
              "layout_update_style",
              "layout_update_props",
              "layout_update_map_view",
              "layout_remove_element" ->
              List.of("element_id");
          default -> List.of();
        };
    ObjectNode schema = ToolSchemas.openObject(mapper);
    ObjectNode properties = (ObjectNode) schema.path("properties");
    for (String field : required) {
      JsonNode fieldSchema =
          "layer_ids".equals(field)
              ? ToolSchemas.array(mapper, ToolSchemas.string(mapper), 1)
              : "visible".equals(field) ? ToolSchemas.bool(mapper) : ToolSchemas.string(mapper);
      properties.set(field, fieldSchema);
    }
    var requiredNode = schema.putArray("required");
    required.forEach(requiredNode::add);
    return schema;
  }

  private static String methodFor(String name, JsonNode arguments) {
    if ("add_layer".equals(name)) {
      return "rpc.ui.map.add_layer_from_geojson";
    }
    return METHODS.get(name);
  }

  private static JsonNode payloadFor(
      String name,
      JsonNode arguments,
      ObjectMapper mapper,
      org.opengis.tool.context.ToolExecutionContext context) {
    ObjectNode payload = (ObjectNode) arguments.deepCopy();
    switch (name) {
      case "add_layer" -> {
        JsonNode geoJson = arguments.get("geojson");
        if ((geoJson == null || geoJson.isNull()) && arguments.path("geojson_path").isString()) {
          java.nio.file.Path path =
              WorkspacePaths.resolve(context, arguments.path("geojson_path").asString());
          try {
            geoJson = mapper.readTree(java.nio.file.Files.readString(path));
          } catch (java.io.IOException | tools.jackson.core.JacksonException exception) {
            throw new ToolException("invalid_geojson", "Cannot load GeoJSON layer", exception);
          }
        } else if (geoJson != null && geoJson.isString()) {
          try {
            geoJson = mapper.readTree(geoJson.asString());
          } catch (tools.jackson.core.JacksonException exception) {
            throw new ToolException("invalid_geojson", "Inline GeoJSON is invalid", exception);
          }
        }
        if (geoJson == null || geoJson.isNull()) {
          throw new ToolException(
              "invalid_arguments", "add_layer requires geojson or geojson_path");
        }
        payload.remove("geojson_path");
        payload.set("geojson", geoJson);
        if (!payload.path("name").isString() || payload.path("name").asString().isBlank()) {
          payload.put("name", "OpenGIS layer");
        }
      }
      case "set_map_camera" -> cameraPayload(arguments, payload, null, null, null);
      case "enter_3d_view" -> cameraPayload(arguments, payload, 60.0, -25.0, 800.0);
      case "exit_3d_view" -> cameraPayload(arguments, payload, 0.0, 0.0, 600.0);
      case "fly_to" -> {
        if (arguments.has("lng") && arguments.has("lat")) {
          payload
              .putArray("center")
              .add(arguments.path("lng").asDouble())
              .add(arguments.path("lat").asDouble());
          payload.remove("lng");
          payload.remove("lat");
        }
      }
      case "update_layer_style" -> stylePayload(arguments, payload, mapper);
      case "set_graduated_style" -> rendererPayload(arguments, payload, "graduated", mapper);
      case "set_categorized_style" -> rendererPayload(arguments, payload, "categorized", mapper);
      case "set_extrusion_style" -> rendererPayload(arguments, payload, "extrusion", mapper);
      case "set_raster_style" -> {
        ObjectNode raster = mapper.createObjectNode();
        arguments
            .properties()
            .forEach(
                entry -> {
                  if (!"layer_id".equals(entry.getKey())) {
                    raster.set(
                        "stops_unit".equals(entry.getKey()) ? "stopsUnit" : entry.getKey(),
                        entry.getValue());
                  }
                });
        payload.removeAll();
        payload.put("layer_id", arguments.path("layer_id").asString());
        payload.set("raster", raster);
      }
      case "update_legend_spec" -> {
        ObjectNode legend = mapper.createObjectNode();
        arguments
            .properties()
            .forEach(
                entry -> {
                  if (!"layer_id".equals(entry.getKey())) {
                    legend.set(entry.getKey(), entry.getValue());
                  }
                });
        payload.removeAll();
        payload.put("layer_id", arguments.path("layer_id").asString());
        payload.set("legend", legend);
      }
      case "add_raster" -> {
        if (!payload.has("path")) {
          String path =
              arguments.path("raster_path").asString(arguments.path("file_path").asString(""));
          if (!path.isBlank()) {
            payload.put("path", WorkspacePaths.resolve(context, path).toString());
          }
        } else {
          payload.put(
              "path", WorkspacePaths.resolve(context, payload.path("path").asString()).toString());
        }
        payload.remove("raster_path");
        payload.remove("file_path");
      }
      default -> {
        // Most map/layout contracts already use the canonical field names.
      }
    }
    return payload;
  }

  private static void cameraPayload(
      JsonNode arguments,
      ObjectNode payload,
      Double defaultPitch,
      Double defaultBearing,
      Double defaultDuration) {
    if (arguments.has("lng") && arguments.has("lat")) {
      payload
          .putArray("center")
          .add(arguments.path("lng").asDouble())
          .add(arguments.path("lat").asDouble());
      payload.remove("lng");
      payload.remove("lat");
    }
    if (!payload.has("pitch") && defaultPitch != null) {
      payload.put("pitch", defaultPitch);
    }
    if (!payload.has("bearing") && defaultBearing != null) {
      payload.put("bearing", defaultBearing);
    }
    if (!payload.has("duration") && defaultDuration != null) {
      payload.put("duration", defaultDuration);
    }
  }

  private static void stylePayload(JsonNode arguments, ObjectNode payload, ObjectMapper mapper) {
    ObjectNode paint = mapper.createObjectNode();
    copy(arguments, "color", paint, "fill-color");
    copy(arguments, "fill_color", paint, "fill-color");
    copy(arguments, "opacity", paint, "fill-opacity");
    copy(arguments, "fill_opacity", paint, "fill-opacity");
    copy(arguments, "line_color", paint, "line-color");
    copy(arguments, "line_width", paint, "line-width");
    copy(arguments, "line_opacity", paint, "line-opacity");
    copy(arguments, "line_dasharray", paint, "line-dasharray");
    copy(arguments, "point_color", paint, "circle-color");
    copy(arguments, "point_size", paint, "circle-radius");
    copy(arguments, "point_opacity", paint, "circle-opacity");
    ObjectNode style = mapper.createObjectNode();
    style.put(
        "type",
        arguments.has("point_size") ? "circle" : arguments.has("line_width") ? "line" : "fill");
    style.set("paint", paint);
    payload.removeAll();
    payload.put("layer_id", arguments.path("layer_id").asString());
    payload.set("style", style);
  }

  private static void rendererPayload(
      JsonNode arguments, ObjectNode payload, String renderer, ObjectMapper mapper) {
    ObjectNode configuration = mapper.createObjectNode();
    if ("graduated".equals(renderer)) {
      configuration.put("field", arguments.path("field").asString());
      configuration.put("method", arguments.path("method").asString("quantile").replace('_', '-'));
      if (arguments.has("classes")) {
        configuration.set("classes", arguments.path("classes"));
      }
      if (arguments.has("breaks")) {
        configuration.set("breaks", arguments.path("breaks"));
      }
      if (arguments.path("palette").isArray()) {
        configuration.set("palette", arguments.path("palette"));
      }
    } else if ("categorized".equals(renderer)) {
      configuration.put("field", arguments.path("field").asString());
      copy(arguments, "colors", configuration, "colors");
      copy(arguments, "categories", configuration, "categories");
      copy(arguments, "max_categories", configuration, "maxCategories");
      copy(arguments, "other_color", configuration, "otherColor");
    } else {
      configuration.put("heightField", arguments.path("height_field").asString());
      if (arguments.has("height_multiplier")) {
        configuration.set("heightMultiplier", arguments.path("height_multiplier"));
      }
      copy(arguments, "base_field", configuration, "baseField");
    }
    payload.removeAll();
    payload.put("layer_id", arguments.path("layer_id").asString());
    payload.put("renderer", renderer);
    payload.set(renderer, configuration);
  }

  private static void copy(
      JsonNode source, String sourceName, ObjectNode target, String targetName) {
    if (source.has(sourceName) && !source.path(sourceName).isNull()) {
      target.set(targetName, source.path(sourceName));
    }
  }

  private static boolean mutating(String name) {
    return !name.startsWith("get_")
        && !name.startsWith("list_")
        && !name.equals("layout_get_state");
  }

  private static String title(String value) {
    String[] words = value.split("_");
    StringBuilder title = new StringBuilder();
    for (String word : words) {
      if (!title.isEmpty()) {
        title.append(' ');
      }
      title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return title.toString();
  }

  private static Map<String, String> createMethods() {
    Map<String, String> methods = new LinkedHashMap<>();
    methods.put("add_layer", "rpc.ui.map.add_layer_from_geojson");
    methods.put("remove_layer", "rpc.ui.map.remove_layer");
    methods.put("list_layers", "rpc.ui.map.list_layers");
    methods.put("get_layer", "rpc.ui.map.get_layer");
    methods.put("get_map_state", "rpc.ui.map.get_state");
    methods.put("query_features", "rpc.ui.map.query_features");
    methods.put("zoom_to_layer", "rpc.ui.map.zoom_to_layer");
    methods.put("fly_to", "rpc.ui.map.fly_to");
    methods.put("set_map_camera", "rpc.ui.map.set_camera");
    methods.put("enter_3d_view", "rpc.ui.map.set_camera");
    methods.put("exit_3d_view", "rpc.ui.map.set_camera");
    methods.put("set_basemap", "rpc.ui.map.set_basemap");
    methods.put("set_basemap_visibility", "rpc.ui.map.set_basemap_visibility");
    methods.put("update_layer_style", "rpc.ui.map.set_layer_style");
    methods.put("set_graduated_style", "rpc.ui.map.set_layer_renderer");
    methods.put("set_categorized_style", "rpc.ui.map.set_layer_renderer");
    methods.put("set_extrusion_style", "rpc.ui.map.set_layer_renderer");
    methods.put("set_layer_visual_variables", "rpc.ui.map.update_visual_variables");
    methods.put("set_layer_filter", "rpc.ui.map.set_layer_filter");
    methods.put("set_layer_label", "rpc.ui.map.set_layer_label");
    methods.put("highlight_features", "rpc.ui.map.highlight_features");
    methods.put("set_layer_order", "rpc.ui.map.set_layer_order");
    methods.put("update_legend_spec", "rpc.ui.map.update_legend_spec");
    methods.put("add_raster", "rpc.ui.map.add_raster_from_file");
    methods.put("get_raster_info", "rpc.ui.map.get_raster_info");
    methods.put("set_raster_style", "rpc.ui.map.set_raster_style");
    methods.put("layout_get_state", "rpc.ui.layout.get_state");
    methods.put("layout_set_page", "rpc.ui.layout.set_page");
    methods.put("layout_add_element", "rpc.ui.layout.add_element");
    methods.put("layout_update_frame", "rpc.ui.layout.update_frame");
    methods.put("layout_update_style", "rpc.ui.layout.update_style");
    methods.put("layout_update_props", "rpc.ui.layout.update_props");
    methods.put("layout_update_map_view", "rpc.ui.layout.update_map_view");
    methods.put("layout_capture_map", "rpc.ui.layout.capture_map");
    methods.put("layout_remove_element", "rpc.ui.layout.remove_element");
    methods.put("layout_export", "rpc.ui.layout.export");
    methods.put("interactive_snapshot", "rpc.ui.chat.interactive_snapshot");
    methods.put("save_plot", "rpc.ui.chat.show_image");
    return methods;
  }
}
