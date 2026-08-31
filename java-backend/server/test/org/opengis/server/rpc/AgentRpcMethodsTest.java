/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.assistant.agent.persistence.RunArchive;
import org.opengis.assistant.agent.session.SessionCoordinator;
import org.opengis.assistant.model.context.CacheObservatory;
import org.opengis.assistant.provider.LlmModelFactory;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.agent.LlmConfigurationState;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentRpcMethodsTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ExecutorService executor;

  @AfterEach
  void closeExecutor() {
    if (executor != null) {
      executor.close();
    }
  }

  @Test
  void configConnectionChatArchiveAndCacheDiagnosticsWork(@TempDir Path workspace)
      throws Exception {
    Harness harness = harness(new AtomicBoolean(false));

    Object configured =
        harness.call(
            "rpc.agent.set_llm_config",
            mapper
                .createObjectNode()
                .put("protocol", "openai")
                .put("model", "local-model")
                .put("api_key", "")
                .put("base_url", "http://127.0.0.1/v1")
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
                    .put("context_window", 1_000_000)
                    .put("workspace_path", workspace.toString())
                    .put("_connection_id", "connection")));
    String runId = started.path("run_id").asString();
    assertThat(started.path("status").asString()).isEqualTo("started");
    assertThat(started.path("context_window").asInt()).isEqualTo(1_000_000);
    waitUntilIdle(harness.sessions);

    RunArchive archive = RunArchive.load(workspace, runId).orElseThrow();
    assertThat(archive.meta().path("status").asString())
        .withFailMessage(archive.meta().toString())
        .isEqualTo("completed");
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
    Harness harness = harness(holdOpen);
    harness.call(
        "rpc.agent.set_llm_config",
        mapper
            .createObjectNode()
            .put("protocol", "openai")
            .put("model", "local-model")
            .put("base_url", "http://127.0.0.1/v1")
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
    String runId = started.path("run_id").asString();
    waitUntilActive(harness.sessions);

    JsonNode interrupted =
        mapper.valueToTree(
            harness.call(
                "rpc.agent.interrupt",
                mapper.createObjectNode().put("workspace_path", workspace.toString())));
    assertThat(interrupted.path("status").asString()).isEqualTo("cancelled");
    holdOpen.set(false);
    waitUntilIdle(harness.sessions);

    RunArchive archive = RunArchive.load(workspace, runId).orElseThrow();
    assertThat(archive.meta().path("status").asString()).isEqualTo("cancelled");
  }

  private Harness harness(AtomicBoolean holdOpen) {
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
    LlmModelFactory clients = ignored -> new TestChatModel(holdOpen);
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
    new AgentRpcMethods(registry, configuration, clients, agents, cache).registerMethods();
    return new Harness(registry, sessions);
  }

  private static final class TestChatModel implements ChatModel {
    private final AtomicBoolean holdOpen;

    private TestChatModel(AtomicBoolean holdOpen) {
      this.holdOpen = holdOpen;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      return response();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      Flux<ChatResponse> output = Flux.just(response());
      return holdOpen.get() ? output.concatWith(Flux.never()) : output;
    }

    private static ChatResponse response() {
      return new ChatResponse(
          List.of(
              new Generation(
                  new AssistantMessage("OK"),
                  ChatGenerationMetadata.builder().finishReason("stop").build())));
    }
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
