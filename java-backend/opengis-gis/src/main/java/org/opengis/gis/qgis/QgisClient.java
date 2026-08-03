package org.opengis.gis.qgis;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One-request-per-connection QGIS MCP client using the existing big-endian framing. */
public final class QgisClient {
  private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
  private static final Set<String> COMMANDS =
      Set.of(
          "ping",
          "get_qgis_info",
          "diagnose",
          "transform_coordinates",
          "load_project",
          "create_new_project",
          "save_project",
          "get_project_info",
          "set_project_crs",
          "add_vector_layer",
          "add_raster_layer",
          "get_layers",
          "remove_layer",
          "find_layer",
          "get_layer_info",
          "get_layer_schema",
          "get_layer_extent",
          "set_active_layer",
          "get_active_layer",
          "set_layer_visibility",
          "zoom_to_layer",
          "get_layer_features",
          "add_features",
          "update_features",
          "delete_features",
          "get_field_statistics",
          "select_features",
          "get_selection",
          "clear_selection",
          "execute_code",
          "execute_processing",
          "list_processing_algorithms",
          "get_algorithm_help",
          "render_map_base64",
          "get_canvas_extent",
          "set_canvas_extent",
          "get_canvas_screenshot",
          "set_layer_style",
          "get_layer_crs",
          "set_layer_crs",
          "get_layer_labeling",
          "set_layer_labeling");

  private final ObjectMapper mapper;
  private final String host;
  private final int port;
  private final Duration timeout;

  public QgisClient(ObjectMapper mapper, String host, int port, Duration timeout) {
    this.mapper = mapper;
    this.host = host == null || host.isBlank() ? "127.0.0.1" : host;
    this.port = Math.max(1, Math.min(port, 65535));
    this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    requireLoopback(this.host);
  }

  public JsonNode call(String command, JsonNode params, CancellationToken cancellation) {
    if (!COMMANDS.contains(command)) {
      throw new ToolException("qgis_unknown_command", "Unknown QGIS command: " + command);
    }
    cancellation.throwIfCancelled();
    ObjectNode request = mapper.createObjectNode().put("type", command);
    request.set(
        "params", params == null || !params.isObject() ? mapper.createObjectNode() : params);
    try (Socket socket = new Socket()) {
      int milliseconds = Math.toIntExact(Math.min(timeout.toMillis(), 30_000));
      socket.connect(new InetSocketAddress(host, port), milliseconds);
      socket.setSoTimeout(milliseconds);
      byte[] payload = mapper.writeValueAsBytes(request);
      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.writeInt(payload.length);
      output.write(payload);
      output.flush();
      DataInputStream input = new DataInputStream(socket.getInputStream());
      int size = input.readInt();
      if (size <= 0 || size > MAX_RESPONSE_BYTES) {
        throw new ToolException("qgis_invalid_frame", "Invalid QGIS response length: " + size);
      }
      byte[] response = input.readNBytes(size);
      if (response.length != size)
        throw new ToolException("qgis_connection_closed", "QGIS closed the connection");
      cancellation.throwIfCancelled();
      JsonNode value = mapper.readTree(response);
      if ("error".equals(value.path("status").asText())) {
        throw new ToolException(
            "qgis_command_failed", value.path("message").asText("QGIS command failed"));
      }
      return value.has("result") ? value.path("result") : value;
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("qgis_connection_failed", "Cannot connect to QGIS MCP", exception);
    }
  }

  private static void requireLoopback(String host) {
    try {
      if (!InetAddress.getByName(host).isLoopbackAddress()) {
        throw new ToolException("unsafe_qgis_host", "QGIS MCP host must resolve to loopback");
      }
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("invalid_qgis_host", "Cannot resolve QGIS MCP host", exception);
    }
  }
}
