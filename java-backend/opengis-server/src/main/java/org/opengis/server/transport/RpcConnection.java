package org.opengis.server.transport;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.opengis.common.protocol.JsonRpcNotification;
import org.opengis.common.protocol.JsonRpcRequest;
import org.opengis.server.rpc.RpcException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One authenticated WebSocket plus its Java-to-UI pending request table. */
public final class RpcConnection {
  private final WebSocketSession session;
  private final ObjectMapper objectMapper;
  private final Map<String, CompletableFuture<JsonNode>> pendingRequests =
      new ConcurrentHashMap<>();
  private final Object sendLock = new Object();

  RpcConnection(WebSocketSession session, ObjectMapper objectMapper) {
    this.session = session;
    this.objectMapper = objectMapper;
  }

  public String id() {
    return session.getId();
  }

  public CompletableFuture<JsonNode> request(String method, Object params) {
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<JsonNode> result = new CompletableFuture<>();
    pendingRequests.put(requestId, result);
    try {
      send(new JsonRpcRequest(requestId, method, params));
    } catch (RuntimeException exception) {
      pendingRequests.remove(requestId);
      result.completeExceptionally(exception);
    }
    return result;
  }

  public void notify(String method, Object params) {
    send(new JsonRpcNotification(method, params));
  }

  public void send(Object message) {
    try {
      String payload = objectMapper.writeValueAsString(message);
      synchronized (sendLock) {
        if (!session.isOpen()) {
          throw new RpcConnectionClosedException(id());
        }
        session.sendMessage(new TextMessage(payload));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to send WebSocket message", exception);
    }
  }

  public boolean acceptResponse(JsonNode message) {
    if (!message.has("id")
        || message.has("method")
        || (!message.has("result") && !message.has("error"))) {
      return false;
    }
    String requestId = message.path("id").asString();
    CompletableFuture<JsonNode> future = pendingRequests.remove(requestId);
    if (future == null) {
      return true;
    }
    if (message.has("error")) {
      JsonNode error = message.path("error");
      future.completeExceptionally(
          new RpcException(
              error.path("code").asInt(),
              error.path("message").asString("RPC error"),
              error.get("data")));
    } else {
      future.complete(message.get("result"));
    }
    return true;
  }

  public int pendingRequestCount() {
    return pendingRequests.size();
  }

  public void close() {
    RpcConnectionClosedException exception = new RpcConnectionClosedException(id());
    pendingRequests.values().forEach(future -> future.completeExceptionally(exception));
    pendingRequests.clear();
  }
}
