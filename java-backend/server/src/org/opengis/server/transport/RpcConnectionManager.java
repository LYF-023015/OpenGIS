/** 文件职责：server 后端领域：承载该领域的核心业务流程。 */
package org.opengis.server.transport;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** Registry of currently authenticated Renderer connections. */
@Component
public class RpcConnectionManager {
  private final ConcurrentHashMap<String, RpcConnection> connections = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper;

  public RpcConnectionManager(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public RpcConnection register(WebSocketSession session) {
    RpcConnection connection = new RpcConnection(session, objectMapper);
    connections.put(connection.id(), connection);
    return connection;
  }

  public RpcConnection require(String connectionId) {
    RpcConnection connection = connections.get(connectionId);
    if (connection == null) {
      throw new IllegalArgumentException("Unknown WebSocket connection: " + connectionId);
    }
    return connection;
  }

  public RpcConnection find(String connectionId) {
    return connections.get(connectionId);
  }

  public void remove(String connectionId) {
    RpcConnection connection = connections.remove(connectionId);
    if (connection != null) {
      connection.close();
    }
  }

  public Set<String> connectionIds() {
    return Set.copyOf(connections.keySet());
  }
}
