package org.opengis.server.transport;

import java.io.IOException;
import java.net.URI;
import org.opengis.agent.session.SessionCoordinator;
import org.opengis.common.protocol.JsonRpcErrorCodes;
import org.opengis.common.protocol.JsonRpcErrorResponse;
import org.opengis.server.lifecycle.WebSocketToken;
import org.opengis.server.rpc.RpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated bidirectional JSON-RPC transport at {@code /ws}. */
@Component
public class OpenGisWebSocketHandler extends TextWebSocketHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpenGisWebSocketHandler.class);

  private final WebSocketToken expectedToken;
  private final RpcDispatcher dispatcher;
  private final RpcConnectionManager connections;
  private final DynamicLayerUpdateBuffer dynamicLayerBuffer;
  private final SessionCoordinator agentSessions;
  private final ObjectMapper objectMapper;

  public OpenGisWebSocketHandler(
      WebSocketToken expectedToken,
      RpcDispatcher dispatcher,
      RpcConnectionManager connections,
      DynamicLayerUpdateBuffer dynamicLayerBuffer,
      SessionCoordinator agentSessions,
      ObjectMapper objectMapper) {
    this.expectedToken = expectedToken;
    this.dispatcher = dispatcher;
    this.connections = connections;
    this.dynamicLayerBuffer = dynamicLayerBuffer;
    this.agentSessions = agentSessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String suppliedToken = queryToken(session.getUri());
    if (!expectedToken.value().equals(suppliedToken)) {
      session.sendMessage(
          new TextMessage(
              objectMapper.writeValueAsString(
                  new JsonRpcErrorResponse(
                      null,
                      JsonRpcErrorCodes.INVALID_WEBSOCKET_TOKEN,
                      "Unauthorized: invalid or missing token",
                      null))));
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    connections.register(session);
    LOGGER.debug("Renderer WebSocket connected: {}", session.getId());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage textMessage)
      throws Exception {
    RpcConnection connection = connections.find(session.getId());
    if (connection == null) {
      session.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    JsonNode message = parse(textMessage.getPayload());
    if (message != null && connection.acceptResponse(message)) {
      return;
    }
    Object response = dispatcher.dispatch(withConnectionId(message, session.getId()));
    if (response != null) {
      connection.send(response);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    agentSessions.cancelConnection(session.getId());
    dynamicLayerBuffer.clearConnection(session.getId());
    connections.remove(session.getId());
    LOGGER.debug("Renderer WebSocket closed: {} ({})", session.getId(), status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception)
      throws IOException {
    LOGGER.debug("Renderer WebSocket transport error: {}", session.getId(), exception);
    session.close(CloseStatus.SERVER_ERROR);
  }

  private JsonNode parse(String payload) {
    try {
      return objectMapper.readTree(payload);
    } catch (JacksonException exception) {
      return null;
    }
  }

  private JsonNode withConnectionId(JsonNode message, String connectionId) {
    if (message == null || !message.isObject() || !message.path("params").isObject()) {
      return message;
    }
    tools.jackson.databind.node.ObjectNode copy =
        (tools.jackson.databind.node.ObjectNode) message.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) copy.path("params"))
        .put("_connection_id", connectionId);
    return copy;
  }

  private static String queryToken(URI uri) {
    if (uri == null) {
      return null;
    }
    return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
  }
}
