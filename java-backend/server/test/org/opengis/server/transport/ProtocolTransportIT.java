/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.opengis.server.OpenGisApplication;
import org.opengis.server.lifecycle.WebSocketToken;
import org.opengis.server.rpc.RpcMethodRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProtocolTransportIT {
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void httpAndWebSocketSupportBothRpcDirectionsAndCloseCleanup() throws Exception {
    try (ConfigurableApplicationContext context =
        SpringApplication.run(
            OpenGisApplication.class,
            "--server.port=0",
            "--logging.file.path=target/phase2-it-logs")) {
      int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
      String token = context.getBean(WebSocketToken.class).value();

      verifyHttpBridge(port);
      verifyInvalidToken(port);

      MessageListener listener = new MessageListener();
      WebSocket socket = connect(port, token, listener);
      RpcConnectionManager connections = context.getBean(RpcConnectionManager.class);
      UiRpcGateway gateway = context.getBean(UiRpcGateway.class);
      waitUntil(() -> connections.connectionIds().size() == 1);
      String connectionId = connections.connectionIds().iterator().next();

      socket
          .sendText(
              "{\"jsonrpc\":\"2.0\",\"id\":\"ws-ping\","
                  + "\"method\":\"rpc.system.ping\",\"params\":{}}",
              true)
          .join();
      JsonNode ping = listener.next(objectMapper);
      assertThat(ping.path("id").asString()).isEqualTo("ws-ping");
      assertThat(ping.path("result").path("runtime").asString()).isEqualTo("java");

      CompletableFuture<JsonNode> uiResult =
          gateway.request(connectionId, "rpc.ui.map.get_state", Map.of("include_layers", true));
      JsonNode uiRequest = listener.next(objectMapper);
      assertThat(uiRequest.path("method").asString()).isEqualTo("rpc.ui.map.get_state");
      socket
          .sendText(
              objectMapper.writeValueAsString(
                  Map.of(
                      "jsonrpc", "2.0",
                      "id", uiRequest.path("id").asString(),
                      "result", Map.of("zoom", 8))),
              true)
          .join();
      assertThat(uiResult.get(5, TimeUnit.SECONDS).path("zoom").asInt()).isEqualTo(8);

      gateway.notify(connectionId, "rpc.ui.chat.show_text", Map.of("text", "phase2-ready"));
      JsonNode notification = listener.next(objectMapper);
      assertThat(notification.path("method").asString()).isEqualTo("rpc.ui.chat.show_text");
      assertThat(notification.path("params").path("text").asString()).isEqualTo("phase2-ready");

      AtomicBoolean rendererNotificationHandled = new AtomicBoolean();
      context
          .getBean(RpcMethodRegistry.class)
          .register(
              "event.phase2.renderer_ready",
              params -> {
                rendererNotificationHandled.set(true);
                return null;
              });
      socket
          .sendText(
              "{\"jsonrpc\":\"2.0\",\"method\":\"event.phase2.renderer_ready\"," + "\"params\":{}}",
              true)
          .join();
      waitUntil(rendererNotificationHandled::get);

      CompletableFuture<JsonNode> abandoned =
          gateway.request(connectionId, "rpc.ui.map.get_state", Map.of());
      listener.next(objectMapper);
      assertThat(connections.require(connectionId).pendingRequestCount()).isEqualTo(1);
      socket.abort();
      waitUntil(() -> connections.connectionIds().isEmpty());
      assertThatThrownBy(() -> abandoned.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(RpcConnection.RpcConnectionClosedException.class);
    }
  }

  private void verifyHttpBridge(int port) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/rpc"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"http-ping\","
                            + "\"method\":\"rpc.system.ping\",\"params\":{}}",
                        StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.path("id").asString()).isEqualTo("http-ping");
    assertThat(body.path("result").path("status").asString()).isEqualTo("ok");
  }

  private void verifyInvalidToken(int port) throws Exception {
    MessageListener listener = new MessageListener();
    WebSocket socket = connect(port, "wrong-token", listener);
    JsonNode response = listener.next(objectMapper);
    assertThat(response.path("error").path("code").asInt()).isEqualTo(-32001);
    socket.abort();
  }

  private WebSocket connect(int port, String token, MessageListener listener) {
    return httpClient
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .header("Origin", "http://localhost:5173")
        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?token=" + token), listener)
        .join();
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static final class MessageListener implements WebSocket.Listener {
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    JsonNode next(ObjectMapper objectMapper) throws Exception {
      String payload = messages.poll(5, TimeUnit.SECONDS);
      assertThat(payload).as("WebSocket message within five seconds").isNotNull();
      return objectMapper.readTree(payload);
    }
  }
}
