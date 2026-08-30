package org.opengis.server.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RpcConnectionTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void closingAConnectionFailsAndClearsEveryPendingRequest() throws Exception {
    List<String> sent = new ArrayList<>();
    RpcConnection connection = connection(sent);

    CompletableFuture<JsonNode> pending =
        connection.request("rpc.ui.map.get_state", Map.of("include_layers", true));
    assertThat(connection.pendingRequestCount()).isEqualTo(1);
    assertThat(sent).hasSize(1);

    connection.close();

    assertThat(connection.pendingRequestCount()).isZero();
    assertThatThrownBy(pending::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(RpcConnectionClosedException.class);
  }

  @Test
  void matchingUiResponseCompletesTheJavaRequest() throws Exception {
    List<String> sent = new ArrayList<>();
    RpcConnection connection = connection(sent);
    CompletableFuture<JsonNode> pending = connection.request("rpc.ui.map.get_state", Map.of());
    String requestId = objectMapper.readTree(sent.getFirst()).path("id").asString();

    boolean accepted =
        connection.acceptResponse(
            objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId + "\",\"result\":{\"zoom\":6}}"));

    assertThat(accepted).isTrue();
    assertThat(pending.join().path("zoom").asInt()).isEqualTo(6);
    assertThat(connection.pendingRequestCount()).isZero();
  }

  @Test
  void lateResponseIsConsumedWithoutCreatingASecondRpcExchange() throws Exception {
    RpcConnection connection = connection(new ArrayList<>());

    boolean accepted =
        connection.acceptResponse(
            objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":\"already-timed-out\",\"result\":{}}"));

    assertThat(accepted).isTrue();
    assertThat(connection.pendingRequestCount()).isZero();
  }

  private RpcConnection connection(List<String> sent) throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("renderer-1");
    when(session.isOpen()).thenReturn(true);
    doAnswer(
            invocation -> {
              TextMessage message = invocation.getArgument(0);
              sent.add(message.getPayload());
              return null;
            })
        .when(session)
        .sendMessage(any(TextMessage.class));
    return new RpcConnection(session, objectMapper);
  }
}
