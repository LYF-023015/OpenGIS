package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.agent.session.SessionCoordinator;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.provider.LlmClientFactory;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.agent.LlmConfigurationState;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class Phase5AgentRpcTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private ExecutorService executor;

  @AfterEach
  void close() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.close();
    }
  }

  @Test
  void configConnectionChatArchiveAndCacheDiagnosticsWork(@TempDir Path workspace)
      throws Exception {
    server = sseServer(new AtomicBoolean(false));
    Harness harness = harness();

    Object configured =
        harness.call(
            "rpc.agent.set_llm_config",
            mapper
                .createObjectNode()
                .put("protocol", "openai")
                .put("model", "local-model")
                .put("api_key", "")
                .put("base_url", endpoint())
                .put("timeout_ms", 2000));
    assertThat(mapper.valueToTree(configured).path("has_api_key").asBoolean()).isFalse();
    assertThat(
            mapper
                .valueToTree(harness.call("rpc.agent.test_connection", mapper.createObjectNode()))
                .path("ok")
                .asBoolean())
        .isTrue();

    JsonNode started =
        mapper.valueToTree(
            harness.call(
                "chat.user_message",
                mapper
                    .createObjectNode()
                    .put("message", "hello")
                    .put("conversation_id", "conversation")
                    .put("workspace_path", workspace.toString())
                    .put("_connection_id", "connection")));
    String runId = started.path("run_id").asText();
    assertThat(started.path("status").asText()).isEqualTo("started");
    waitUntilIdle(harness.sessions);

    RunArchive archive = RunArchive.load(workspace, runId).orElseThrow();
    assertThat(archive.meta().path("status").asText()).isEqualTo("completed");
    assertThat(archive.read("llm_usage.jsonl")).hasSize(1);
    assertThat(
            mapper
                .valueToTree(harness.call("rpc.agent.cache.stats", mapper.createObjectNode()))
                .path("requests")
                .asLong())
        .isPositive();
    assertThat(
            mapper
                .valueToTree(harness.call("rpc.agent.providers.list", mapper.createObjectNode()))
                .path("providers"))
        .hasSize(24);
  }

  @Test
  void interruptTerminatesAnActiveProviderStream(@TempDir Path workspace) throws Exception {
    AtomicBoolean holdOpen = new AtomicBoolean(true);
    server = sseServer(holdOpen);
    Harness harness = harness();
    harness.call(
        "rpc.agent.set_llm_config",
        mapper
            .createObjectNode()
            .put("protocol", "openai")
            .put("model", "local-model")
            .put("base_url", endpoint())
            .put("timeout_ms", 10_000));
    JsonNode started =
        mapper.valueToTree(
            harness.call(
                "chat.user_message",
                mapper
                    .createObjectNode()
                    .put("message", "wait")
                    .put("conversation_id", "conversation")
                    .put("workspace_path", workspace.toString())
                    .put("_connection_id", "connection")));
    String runId = started.path("run_id").asText();
    waitUntilActive(harness.sessions);

    JsonNode interrupted =
        mapper.valueToTree(
            harness.call(
                "rpc.agent.interrupt",
                mapper.createObjectNode().put("workspace_path", workspace.toString())));
    assertThat(interrupted.path("status").asText()).isEqualTo("cancelled");
    holdOpen.set(false);
    waitUntilIdle(harness.sessions);

    RunArchive archive = RunArchive.load(workspace, runId).orElseThrow();
    assertThat(archive.meta().path("status").asText()).isEqualTo("cancelled");
  }

  private Harness harness() {
    RpcMethodRegistry registry = new RpcMethodRegistry();
    LlmConfigurationState configuration = new LlmConfigurationState();
    SessionCoordinator sessions = new SessionCoordinator();
    CacheObservatory cache = new CacheObservatory(mapper);
    ToolRegistry tools = new ToolRegistry();
    ToolRuntime runtime =
        new ToolRuntime(
            tools,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    LlmClientFactory clients = new LlmClientFactory(HttpClient.newHttpClient(), mapper);
    AgentApplicationService agents =
        new AgentApplicationService(
            executor,
            sessions,
            configuration,
            clients,
            tools,
            runtime,
            cache,
            mock(UiRpcGateway.class),
            mapper);
    new Phase5RpcMethods(registry, configuration, clients, agents, cache).registerMethods();
    return new Harness(registry, sessions);
  }

  private HttpServer sseServer(AtomicBoolean holdOpen) throws Exception {
    HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    value.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try {
            exchange
                .getResponseBody()
                .write(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            while (holdOpen.get()) {
              exchange
                  .getResponseBody()
                  .write("data: {\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
              exchange.getResponseBody().flush();
              Thread.sleep(25);
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          } catch (Exception ignored) {
            // Cancellation closes the client side stream.
          } finally {
            exchange.close();
          }
        });
    value.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    value.start();
    return value;
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  private static void waitUntilActive(SessionCoordinator sessions) throws Exception {
    waitFor(() -> !sessions.activeRuns().isEmpty());
  }

  private static void waitUntilIdle(SessionCoordinator sessions) throws Exception {
    waitFor(() -> sessions.activeRuns().isEmpty());
  }

  private static void waitFor(Check check) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!check.done() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(check.done()).isTrue();
  }

  @FunctionalInterface
  private interface Check {
    boolean done();
  }

  private record Harness(RpcMethodRegistry registry, SessionCoordinator sessions) {
    Object call(String method, ObjectNode params) {
      return registry.find(method).orElseThrow().handle(params);
    }
  }
}
