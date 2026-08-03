package org.opengis.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.context.AgentRunContext;
import org.opengis.agent.context.ContextManager;
import org.opengis.agent.execution.ToolSchemaProjector;
import org.opengis.agent.profile.AgentProfile;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.context.RequestCompactor;
import org.opengis.ai.context.TokenEstimator;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.model.LlmUsage;
import org.opengis.ai.port.LlmClient;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LoopKernelTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void functionCallIsSettledThenModelProducesFinalText(@TempDir Path workspace) {
    ToolRegistry registry = registry(successTool());
    Queue<LlmResponse> responses = new ArrayDeque<>();
    responses.add(toolResponse("call-1", "sample"));
    responses.add(new LlmResponse("Completed", List.of(), "stop", new LlmUsage(40, 5, 20, 20, 0)));
    ContextManager contexts = new ContextManager(workspace);
    LoopKernel kernel = kernel(workspace, registry, queued(responses), contexts);

    AgentLoopResult result = kernel.run(request(Duration.ofSeconds(2)), context(workspace));

    assertThat(result.completed()).isTrue();
    assertThat(result.finalAnswer()).isEqualTo("Completed");
    assertThat(result.providerTurns()).isEqualTo(2);
    assertThat(result.toolSteps()).isEqualTo(1);
    assertThat(contexts.messages("conversation"))
        .anyMatch(message -> message.role().name().equals("TOOL"));
  }

  @Test
  void repeatedIdenticalToolFailureStopsLoop(@TempDir Path workspace) {
    ToolRegistry registry = registry(failingTool());
    Queue<LlmResponse> responses = new ArrayDeque<>();
    responses.add(toolResponse("call-1", "fail"));
    responses.add(toolResponse("call-2", "fail"));
    responses.add(toolResponse("call-3", "fail"));
    LoopKernel kernel =
        kernel(workspace, registry, queued(responses), new ContextManager(workspace));

    AgentLoopResult result = kernel.run(request(Duration.ofSeconds(2)), context(workspace));

    assertThat(result.stopReason()).isEqualTo(StopReason.REPEATED_FAILURE);
    assertThat(result.toolSteps()).isEqualTo(3);
    assertThat(result.error()).contains("Repeated identical failure");
  }

  @Test
  void stuckToolIsInterruptedAndTerminatesRun(@TempDir Path workspace) {
    ToolRegistry registry = registry(slowTool());
    Queue<LlmResponse> responses = new ArrayDeque<>();
    responses.add(toolResponse("call-1", "slow"));
    LoopKernel kernel =
        kernel(workspace, registry, queued(responses), new ContextManager(workspace));
    AgentLoopRequest request = request(Duration.ofMillis(75));
    long started = System.nanoTime();

    AgentLoopResult result = kernel.run(request, context(workspace));

    assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_TIMEOUT);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void repeatedPromiseWithoutToolIsClassifiedAsDeviation(@TempDir Path workspace) {
    ToolRegistry registry = registry(successTool());
    Queue<LlmResponse> responses = new ArrayDeque<>();
    responses.add(new LlmResponse("Next I will read the file.", List.of(), "stop", LlmUsage.EMPTY));
    responses.add(new LlmResponse("Next I will read the file.", List.of(), "stop", LlmUsage.EMPTY));
    LoopKernel kernel =
        kernel(workspace, registry, queued(responses), new ContextManager(workspace));

    AgentLoopResult result = kernel.run(request(Duration.ofSeconds(2)), context(workspace));

    assertThat(result.stopReason()).isEqualTo(StopReason.DEVIATION);
    assertThat(result.providerTurns()).isEqualTo(2);
  }

  private LoopKernel kernel(
      Path workspace, ToolRegistry registry, LlmClient llm, ContextManager contexts) {
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    return new LoopKernel(
        contexts,
        registry,
        new ToolSchemaProjector(),
        new RequestCompactor(new TokenEstimator(mapper)),
        new CacheObservatory(mapper),
        new TurnRunner(llm, runtime, mapper, new RetryPolicy(2, Duration.ofMillis(1))));
  }

  private AgentRunContext context(Path workspace) {
    return new AgentRunContext(
        workspace,
        "run-1",
        "conversation",
        "connection",
        AgentProfile.gisBuild(),
        new CancellationToken(),
        ignored -> {},
        ignored -> {},
        null,
        Map.of());
  }

  private AgentLoopRequest request(Duration toolTimeout) {
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
        toolTimeout);
  }

  private LlmClient queued(Queue<LlmResponse> responses) {
    return (request, onChunk, cancellation) -> {
      if (responses.isEmpty()) {
        throw new AssertionError("Unexpected provider turn: " + request.messages().size());
      }
      return responses.remove();
    };
  }

  private LlmResponse toolResponse(String id, String tool) {
    return new LlmResponse(
        "",
        List.of(new LlmToolCall(id, tool, mapper.createObjectNode())),
        "tool_calls",
        LlmUsage.EMPTY);
  }

  private ToolRegistry registry(OpenGisTool tool) {
    return new ToolRegistry().register(tool);
  }

  private OpenGisTool successTool() {
    return tool("sample", (arguments, context) -> mapper.createObjectNode().put("value", "ok"));
  }

  private OpenGisTool failingTool() {
    return tool(
        "fail",
        (arguments, context) -> {
          throw new IllegalStateException("same failure 123");
        });
  }

  private OpenGisTool slowTool() {
    return tool(
        "slow",
        (arguments, context) -> {
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          context.cancellation().throwIfCancelled();
          return mapper.createObjectNode();
        });
  }

  private OpenGisTool tool(String name, Executor executor) {
    ToolDefinition definition =
        new ToolDefinition(
            name,
            name,
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
        return executor.execute(arguments, context);
      }
    };
  }

  @FunctionalInterface
  private interface Executor {
    JsonNode execute(JsonNode arguments, org.opengis.tool.context.ToolExecutionContext context);
  }
}
