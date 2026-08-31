/** 文件职责：agent 后端领域：承载该领域的核心业务流程。 */
package org.opengis.assistant.agent.spring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opengis.assistant.agent.context.AgentRunContext;
import org.opengis.assistant.agent.context.ContextManager;
import org.opengis.assistant.agent.loop.AgentStopException;
import org.opengis.assistant.agent.loop.RuntimeControl;
import org.opengis.assistant.agent.loop.StopReason;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmToolCall;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolResult;
import org.opengis.tool.api.ToolStatus;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Executes Spring AI tool calls through the audited OpenGIS tool runtime. */
public final class OpenGisToolCallingManager implements ToolCallingManager {
  private final ToolRuntime tools;
  private final RuntimeControl control;
  private final AgentRunContext context;
  private final ContextManager contexts;
  private final ObjectMapper mapper;

  public OpenGisToolCallingManager(
      ToolRuntime tools,
      RuntimeControl control,
      AgentRunContext context,
      ContextManager contexts,
      ObjectMapper mapper) {
    this.tools = tools;
    this.control = control;
    this.context = context;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @Override
  public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
    return options.getToolCallbacks().stream()
        .map(callback -> callback.getToolDefinition())
        .toList();
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
    if (response == null || response.getResult() == null) {
      throw new AgentStopException(StopReason.ERROR, "Spring AI returned no tool-call result");
    }
    AssistantMessage assistant = response.getResult().getOutput();
    List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
    List<LlmToolCall> persistedCalls =
        calls.stream()
            .map(
                call ->
                    new LlmToolCall(
                        call.id(), call.name(), parseArguments(call.name(), call.arguments())))
            .toList();
    contexts.append(
        context.conversationId(), LlmMessage.assistant(assistant.getText(), persistedCalls));

    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
    for (int index = 0; index < calls.size(); index++) {
      AssistantMessage.ToolCall call = calls.get(index);
      LlmToolCall persisted = persistedCalls.get(index);
      control.beforeTool();
      Instant started = Instant.now();
      ToolResult result = executeWithTimeout(persisted);
      long duration = java.time.Duration.between(started, Instant.now()).toMillis();
      String content = mapper.writeValueAsString(result);
      contexts.append(context.conversationId(), LlmMessage.tool(call.id(), call.name(), content));
      responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), content));
      context.emit(
          "agent.tool.settled",
          Map.of(
              "call_id", call.id(),
              "name", call.name(),
              "status", result.status().name().toLowerCase(java.util.Locale.ROOT),
              "duration_ms", duration));
      if (result.status() == ToolStatus.CANCELLED) {
        throw new AgentStopException(StopReason.CANCELLED, "Tool execution was cancelled");
      }
      if (!result.success()) {
        String error = result.error() == null ? "unknown tool failure" : result.error().message();
        control.recordFailure(call.name(), error);
      }
    }

    List<Message> history = new ArrayList<>(prompt.getInstructions());
    history.add(assistant);
    history.add(ToolResponseMessage.builder().responses(responses).build());
    return ToolExecutionResult.builder().conversationHistory(history).build();
  }

  private JsonNode parseArguments(String toolName, String arguments) {
    try {
      JsonNode parsed =
          mapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
      return parsed == null ? mapper.createObjectNode() : parsed;
    } catch (RuntimeException exception) {
      throw new AgentStopException(
          StopReason.ERROR, "Invalid JSON arguments for tool: " + toolName, exception);
    }
  }

  @SuppressWarnings("try")
  private ToolResult executeWithTimeout(LlmToolCall call) {
    ToolExecutionContext toolContext =
        new ToolExecutionContext(
            context.workspace(),
            context.runId(),
            context.conversationId(),
            context.profile().name(),
            context.permissionOverrides(),
            PermissionAction.ALLOW,
            context.cancellation(),
            context.toolEvents(),
            context.uiRpc());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          executor.submit(
              () ->
                  tools.execute(
                      new ToolCall(call.id(), call.name(), call.arguments()), toolContext));
      try (AutoCloseable ignored = context.cancellation().onCancel(() -> future.cancel(true))) {
        return future.get(Math.max(1, control.toolTimeout().toMillis()), TimeUnit.MILLISECONDS);
      } catch (TimeoutException exception) {
        context.cancellation().cancel();
        future.cancel(true);
        throw new AgentStopException(
            StopReason.TOOL_TIMEOUT, "Tool timed out: " + call.name(), exception);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        context.cancellation().cancel();
        future.cancel(true);
        throw new AgentStopException(StopReason.CANCELLED, "Tool wait was interrupted", exception);
      } catch (ExecutionException exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        throw new AgentStopException(
            StopReason.ERROR, "Tool runtime failed: " + call.name(), cause);
      } catch (CancellationException exception) {
        throw new AgentStopException(
            StopReason.CANCELLED, "Tool execution was cancelled", exception);
      } catch (AgentStopException exception) {
        throw exception;
      } catch (Exception exception) {
        throw new AgentStopException(
            StopReason.ERROR, "Cannot register tool cancellation", exception);
      }
    }
  }
}
