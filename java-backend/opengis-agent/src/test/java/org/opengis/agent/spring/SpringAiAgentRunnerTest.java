package org.opengis.agent.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.context.AgentRunContext;
import org.opengis.agent.context.ContextManager;
import org.opengis.agent.execution.ToolSchemaProjector;
import org.opengis.agent.loop.AgentLoopRequest;
import org.opengis.agent.loop.AgentLoopResult;
import org.opengis.agent.loop.StopReason;
import org.opengis.agent.profile.AgentMode;
import org.opengis.agent.profile.AgentProfile;
import org.opengis.agent.profile.PermissionLevel;
import org.opengis.agent.telemetry.AgentEvent;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.context.CanonicalRequest;
import org.opengis.ai.context.RequestCompactor;
import org.opengis.ai.context.TokenEstimator;
import org.opengis.ai.model.LlmMessage;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.CancellationToken;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SpringAiAgentRunnerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void springAdvisorSettlesOpenGisToolThenReturnsFinalText(@TempDir Path workspace) {
    ToolRegistry registry = new ToolRegistry().register(sampleTool());
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    ContextManager contexts = new ContextManager(workspace);
    List<AgentEvent> events = new ArrayList<>();
    SpringAiAgentRunner runner =
        new SpringAiAgentRunner(
            contexts,
            registry,
            new ToolSchemaProjector(),
            new RequestCompactor(new TokenEstimator(mapper)),
            new CacheObservatory(mapper),
            twoTurnModel(),
            runtime,
            mapper);

    AgentLoopResult result = runner.run(request(), context(workspace, events));

    assertThat(result.completed()).withFailMessage(result.toString()).isTrue();
    assertThat(result.finalAnswer()).isEqualTo("Completed with Spring AI");
    assertThat(result.providerTurns()).isEqualTo(2);
    assertThat(contexts.messages("conversation"))
        .anyMatch(message -> message.role().name().equals("TOOL"));
    assertThat(result.toolSteps()).isEqualTo(1);
    assertThat(events)
        .filteredOn(event -> event.type().equals("agent.provider.chunk"))
        .extracting(event -> event.data().get("text"))
        .containsSubsequence("Completed ", "with ", "Spring AI");
  }

  @Test
  void stepLimitUsesOneNoToolProviderTurnForFinalAnswer(@TempDir Path workspace) {
    ToolRegistry registry = new ToolRegistry().register(sampleTool());
    ToolRuntime runtime = toolRuntime(registry);
    ContextManager contexts = new ContextManager(workspace);
    List<AgentEvent> events = new ArrayList<>();
    AtomicInteger finalizationCalls = new AtomicInteger();
    SpringAiAgentRunner runner =
        runner(
            contexts,
            registry,
            runtime,
            boundedModel("已完成数据检查；尚未生成地图，请从当前会话继续。", finalizationCalls, false));

    AgentLoopResult result =
        runner.run(request(), context(workspace, events, profileWithLimits(1, 2)));

    assertThat(result.status()).isEqualTo("error");
    assertThat(result.stopReason()).isEqualTo(StopReason.STEP_LIMIT);
    assertThat(result.finalAnswer()).isEqualTo("已完成数据检查；尚未生成地图，请从当前会话继续。");
    assertThat(result.providerTurns()).isEqualTo(2);
    assertThat(result.toolSteps()).isEqualTo(1);
    assertThat(finalizationCalls).hasValue(1);
    assertThat(events)
        .filteredOn(event -> event.type().equals("agent.provider.chunk"))
        .anyMatch(event -> "model-finalization".equals(event.data().get("source")));
  }

  @Test
  void stepLimitFallsBackToDeterministicAnswerWhenFinalizationFails(@TempDir Path workspace) {
    ToolRegistry registry = new ToolRegistry().register(sampleTool());
    ToolRuntime runtime = toolRuntime(registry);
    ContextManager contexts = new ContextManager(workspace);
    List<AgentEvent> events = new ArrayList<>();
    SpringAiAgentRunner runner =
        runner(contexts, registry, runtime, boundedModel("", new AtomicInteger(), true));

    AgentLoopResult result =
        runner.run(request(), context(workspace, events, profileWithLimits(1, 2)));

    assertThat(result.status()).isEqualTo("error");
    assertThat(result.stopReason()).isEqualTo(StopReason.STEP_LIMIT);
    assertThat(result.finalAnswer()).contains("调用上限").contains("继续任务");
    assertThat(events)
        .filteredOn(event -> event.type().equals("agent.provider.chunk"))
        .anyMatch(event -> "runtime-fallback".equals(event.data().get("source")));
  }

  @Test
  void highContextPressureCreatesAndReusesPersistentSemanticSummary(@TempDir Path workspace) {
    ToolRegistry registry = new ToolRegistry();
    ToolRuntime runtime = toolRuntime(registry);
    ContextManager contexts = new ContextManager(workspace);
    for (int index = 0; index < 12; index++) {
      contexts.append(
          "conversation",
          LlmMessage.user("历史 GIS 任务 " + index + "：" + "建筑物坐标系 EPSG:4326。".repeat(80)));
    }
    List<AgentEvent> events = new ArrayList<>();
    AtomicInteger summaryCalls = new AtomicInteger();
    ChatModel model = semanticSummaryModel(summaryCalls);

    AgentLoopRequest request =
        new AgentLoopRequest(
            "继续检查建筑物图层",
            "test",
            "test-model",
            "You are OpenGIS.",
            "",
            "",
            "",
            0.2,
            128,
            3_200,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2));
    AgentLoopResult result =
        runner(contexts, registry, runtime, model).run(request, context(workspace, events));

    CanonicalRequest rebuilt =
        contexts.buildRequest(
            "conversation",
            "test-model",
            "system",
            "",
            "",
            "",
            List.of(),
            0.2,
            128,
            Duration.ofSeconds(2));
    String prompt =
        rebuilt.messages().stream()
            .map(LlmMessage::content)
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThat(result.completed()).isTrue();
    assertThat(summaryCalls).hasValue(1);
    assertThat(prompt).contains("项目建筑物数据使用 EPSG:4326");
    assertThat(events)
        .anyMatch(
            event ->
                event.type().equals("agent.context.compacted")
                    && event.data().get("mode").equals("semantic-summary"));
  }

  private SpringAiAgentRunner runner(
      ContextManager contexts, ToolRegistry registry, ToolRuntime runtime, ChatModel model) {
    return new SpringAiAgentRunner(
        contexts,
        registry,
        new ToolSchemaProjector(),
        new RequestCompactor(new TokenEstimator(mapper)),
        new CacheObservatory(mapper),
        model,
        runtime,
        mapper);
  }

  private ToolRuntime toolRuntime(ToolRegistry registry) {
    return new ToolRuntime(
        registry,
        new JsonSchemaValidator(),
        new PermissionRuntime(PermissionRuleSource.empty()),
        new ArtifactMaterializer(),
        mapper);
  }

  private ChatModel boundedModel(
      String finalAnswer, AtomicInteger finalizationCalls, boolean failFinalization) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        finalizationCalls.incrementAndGet();
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
            .isNullOrEmpty();
        if (failFinalization) {
          throw new IllegalStateException("provider unavailable");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(finalAnswer))));
      }

      @Override
      public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        AssistantMessage output =
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall("call-limit", "function", "sample", "{}")))
                .build();
        return Flux.just(
            new ChatResponse(
                List.of(
                    new Generation(
                        output,
                        ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
      }
    };
  }

  private ChatModel twoTurnModel() {
    AtomicInteger calls = new AtomicInteger();
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).hasSize(1);
        return response(calls.getAndIncrement());
      }

      @Override
      public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        int turn = calls.getAndIncrement();
        if (turn == 0) {
          return Flux.just(response(turn));
        }
        return Flux.just(
            textResponse("Completed ", ""),
            textResponse("with ", ""),
            textResponse("Spring AI", "stop"));
      }

      private ChatResponse response(int turn) {
        AssistantMessage output =
            turn == 0
                ? AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall("call-1", "function", "sample", "{}")))
                    .build()
                : new AssistantMessage("Completed with Spring AI");
        String finishReason = turn == 0 ? "tool_calls" : "stop";
        ChatResponse response =
            new ChatResponse(
                List.of(
                    new Generation(
                        output,
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())));
        assertThat(response.hasToolCalls()).isEqualTo(turn == 0);
        return response;
      }

      private ChatResponse textResponse(String text, String finishReason) {
        return new ChatResponse(
            List.of(
                new Generation(
                    new AssistantMessage(text),
                    ChatGenerationMetadata.builder().finishReason(finishReason).build())));
      }
    };
  }

  private ChatModel semanticSummaryModel(AtomicInteger summaryCalls) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        summaryCalls.incrementAndGet();
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage("## 项目事实\n- 项目建筑物数据使用 EPSG:4326。"))));
      }

      @Override
      public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("检查完成。"),
                        ChatGenerationMetadata.builder().finishReason("stop").build()))));
      }
    };
  }

  private AgentRunContext context(Path workspace, List<AgentEvent> events) {
    return context(workspace, events, AgentProfile.gisBuild());
  }

  private AgentRunContext context(Path workspace, List<AgentEvent> events, AgentProfile profile) {
    return new AgentRunContext(
        workspace,
        "run-1",
        "conversation",
        "connection",
        profile,
        new CancellationToken(),
        events::add,
        ignored -> {},
        null,
        Map.of());
  }

  private AgentProfile profileWithLimits(int providerTurns, int toolSteps) {
    return new AgentProfile(
        "bounded-test",
        AgentMode.BUILD,
        "Bounded test profile",
        List.of("core"),
        PermissionLevel.SAFE_WRITE,
        providerTurns,
        false,
        "",
        Map.of("max_provider_turns", providerTurns, "max_tool_steps", toolSteps));
  }

  private AgentLoopRequest request() {
    return new AgentLoopRequest(
        "inspect data",
        "test",
        "test-model",
        "You are OpenGIS.",
        "Tools are available.",
        "Use function calling.",
        "",
        0.2,
        128,
        16_000,
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(2));
  }

  private OpenGisTool sampleTool() {
    ToolDefinition definition =
        new ToolDefinition(
            "sample",
            "sample",
            "test tool",
            "test",
            "core",
            "1",
            ToolRisk.READ,
            mapper.createObjectNode().put("type", "object"),
            List.of());
    return new OpenGisTool() {
      @Override
      public ToolDefinition definition() {
        return definition;
      }

      @Override
      public JsonNode execute(
          JsonNode arguments, org.opengis.tool.context.ToolExecutionContext context) {
        return mapper.createObjectNode().put("value", "ok");
      }
    };
  }
}
