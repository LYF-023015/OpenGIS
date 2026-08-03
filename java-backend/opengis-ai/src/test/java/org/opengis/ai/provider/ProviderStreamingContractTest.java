package org.opengis.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengis.ai.model.LlmChunk;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmRequest;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmToolDefinition;
import org.opengis.ai.port.LlmException;
import org.opengis.framework.concurrent.CancellationSignal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProviderStreamingContractTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void openAiCompatibleStreamsTextToolsUsageAndCorrectRequest() throws Exception {
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    server =
        server(
            "/v1/chat/completions",
            exchange -> {
              captured.set(mapper.readTree(exchange.getRequestBody()));
              assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                  .isEqualTo("Bearer secret");
              sse(
                  exchange,
                  "{\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}",
                  "{\"choices\":[{\"delta\":{\"content\":\"GIS\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                  "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":12}}}",
                  "[DONE]");
            });
    ProviderConfig config = config(ProviderProtocol.OPENAI, "/v1", Duration.ofSeconds(2));
    List<LlmChunk> chunks = new ArrayList<>();
    LlmResponse response =
        new OpenAiCompatibleClient(config, HttpClient.newHttpClient(), mapper)
            .complete(request(Duration.ofSeconds(2)), chunks::add, CancellationSignal.NONE);

    assertThat(response.content()).isEqualTo("Hello GIS");
    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.finishReason()).isEqualTo("tool_calls");
    assertThat(response.toolCalls().getFirst().arguments().path("path").asText())
        .isEqualTo("a.txt");
    assertThat(response.usage().cachedTokens()).isEqualTo(12);
    assertThat(captured.get().path("stream").asBoolean()).isTrue();
    assertThat(captured.get().path("tools").get(0).path("function").path("name").asText())
        .isEqualTo("read");
    assertThat(chunks).isNotEmpty();
  }

  @Test
  void anthropicMessagesStreamsTextToolAndCacheUsage() throws Exception {
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    server =
        server(
            "/v1/messages",
            exchange -> {
              captured.set(mapper.readTree(exchange.getRequestBody()));
              assertThat(exchange.getRequestHeaders().getFirst("x-api-key")).isEqualTo("secret");
              sse(
                  exchange,
                  "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":30,\"cache_read_input_tokens\":20}}}",
                  "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Working\"}}",
                  "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"read\",\"input\":{}}}",
                  "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"map.geojson\\\"}\"}}",
                  "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"input_tokens\":30,\"output_tokens\":5,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":2}}",
                  "{\"type\":\"message_stop\"}");
            });
    ProviderConfig config = config(ProviderProtocol.ANTHROPIC, "", Duration.ofSeconds(2));
    LlmResponse response =
        new AnthropicMessagesClient(config, HttpClient.newHttpClient(), mapper)
            .complete(request(Duration.ofSeconds(2)));

    assertThat(response.content()).isEqualTo("Working");
    assertThat(response.toolCalls().getFirst().name()).isEqualTo("read");
    assertThat(response.finishReason()).isEqualTo("tool_use");
    assertThat(response.usage().cacheReadTokens()).isEqualTo(20);
    assertThat(captured.get().path("system").get(0).path("cache_control").path("type").asText())
        .isEqualTo("ephemeral");
    assertThat(captured.get().path("tools").get(0).path("input_schema").isObject()).isTrue();
  }

  @Test
  void providerTimeoutTerminatesRequest() throws Exception {
    server =
        server(
            "/v1/chat/completions",
            exchange -> {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              sse(exchange, "[DONE]");
            });
    ProviderConfig config = config(ProviderProtocol.OPENAI, "/v1", Duration.ofMillis(100));

    assertThatThrownBy(
            () ->
                new OpenAiCompatibleClient(config, HttpClient.newHttpClient(), mapper)
                    .complete(request(Duration.ofMillis(100))))
        .isInstanceOf(LlmException.class)
        .satisfies(
            error ->
                assertThat(((LlmException) error).error().code()).isEqualTo("provider_timeout"));
  }

  @Test
  void cancellationClosesActiveProviderStream() throws Exception {
    CountDownLatch connected = new CountDownLatch(1);
    server =
        server(
            "/v1/chat/completions",
            exchange -> {
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
              exchange.sendResponseHeaders(200, 0);
              connected.countDown();
              try {
                for (int index = 0; index < 100; index++) {
                  exchange
                      .getResponseBody()
                      .write("data: {\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
                  exchange.getResponseBody().flush();
                  Thread.sleep(50);
                }
              } catch (Exception ignored) {
                // Client cancellation closes the response stream.
              } finally {
                exchange.close();
              }
            });
    TestCancellation cancellation = new TestCancellation();
    ProviderConfig config = config(ProviderProtocol.OPENAI, "/v1", Duration.ofSeconds(10));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          executor.submit(
              () ->
                  new OpenAiCompatibleClient(config, HttpClient.newHttpClient(), mapper)
                      .complete(request(Duration.ofSeconds(10)), ignored -> {}, cancellation));
      assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
      cancellation.cancel();
      assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(LlmException.class);
    }
  }

  @Test
  void catalogPreservesAllPublicProviderPresets() {
    assertThat(ProviderCatalog.presets()).hasSize(24);
    assertThat(ProviderCatalog.presets())
        .filteredOn(preset -> preset.protocol() == ProviderProtocol.ANTHROPIC)
        .extracting(ProviderPreset::id)
        .containsExactly("anthropic", "minimax");
  }

  @Test
  void azureUsesApiKeyHeaderAndPreservesDeploymentQuery() throws Exception {
    server =
        server(
            "/openai/deployments/demo/chat/completions",
            exchange -> {
              assertThat(exchange.getRequestURI().getQuery()).isEqualTo("api-version=2024-10-21");
              assertThat(exchange.getRequestHeaders().getFirst("api-key")).isEqualTo("secret");
              assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
              sse(
                  exchange,
                  "{\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}",
                  "[DONE]");
            });
    ProviderConfig config =
        new ProviderConfig(
            "azure",
            ProviderProtocol.OPENAI,
            "deployment",
            "secret",
            URI.create(
                "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/openai/deployments/demo/chat/completions?api-version=2024-10-21"),
            0.2,
            128,
            Duration.ofSeconds(2));

    LlmResponse response =
        new OpenAiCompatibleClient(config, HttpClient.newHttpClient(), mapper)
            .complete(request(Duration.ofSeconds(2)));

    assertThat(response.content()).isEqualTo("OK");
  }

  private ProviderConfig config(ProviderProtocol protocol, String suffix, Duration timeout) {
    return new ProviderConfig(
        "test",
        protocol,
        "test-model",
        "secret",
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + suffix),
        0.2,
        128,
        timeout);
  }

  private LlmRequest request(Duration timeout) {
    return new LlmRequest(
        "ignored",
        List.of(LlmMessage.system("system"), LlmMessage.user("hello")),
        List.of(
            new LlmToolDefinition(
                "read", "Read file", mapper.createObjectNode().put("type", "object"))),
        0.2,
        128,
        timeout,
        Map.of("cache_stable_prefix", true));
  }

  private HttpServer server(String path, ThrowingHandler handler) throws IOException {
    HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    value.createContext(
        path,
        exchange -> {
          try {
            handler.handle(exchange);
          } catch (Exception exception) {
            exchange.close();
          }
        });
    value.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    value.start();
    return value;
  }

  private static void sse(HttpExchange exchange, String... data) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    for (String item : data) {
      exchange.getResponseBody().write(("data: " + item + "\n\n").getBytes(StandardCharsets.UTF_8));
    }
    exchange.close();
  }

  @FunctionalInterface
  private interface ThrowingHandler {
    void handle(HttpExchange exchange) throws Exception;
  }

  private static final class TestCancellation implements CancellationSignal {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        listeners.forEach(Runnable::run);
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public AutoCloseable onCancel(Runnable cleanup) {
      listeners.add(cleanup);
      if (cancelled.get() && listeners.remove(cleanup)) {
        cleanup.run();
      }
      return () -> listeners.remove(cleanup);
    }
  }
}
