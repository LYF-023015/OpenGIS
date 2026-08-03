package org.opengis.server.transport;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Java application-facing API for requests and notifications sent to the Renderer. */
@Component
public class UiRpcGateway {
  private final RpcConnectionManager connections;
  private final DynamicLayerUpdateBuffer dynamicLayerBuffer;

  public UiRpcGateway(
      RpcConnectionManager connections, DynamicLayerUpdateBuffer dynamicLayerBuffer) {
    this.connections = connections;
    this.dynamicLayerBuffer = dynamicLayerBuffer;
  }

  public CompletableFuture<JsonNode> request(String connectionId, String method, Object params) {
    return connections.require(connectionId).request(method, params);
  }

  public void notify(String connectionId, String method, Map<String, Object> params) {
    RpcConnection connection = connections.require(connectionId);
    if (DynamicLayerUpdateBuffer.METHOD.equals(method)) {
      dynamicLayerBuffer.enqueue(connection, params);
    } else {
      connection.notify(method, params);
    }
  }

  public Set<String> connectionIds() {
    return connections.connectionIds();
  }
}
