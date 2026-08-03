package org.opengis.agent.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opengis.agent.context.AgentRunContext;
import org.opengis.ai.context.CanonicalRequest;
import org.opengis.ai.model.LlmChunk;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.port.LlmClient;
import org.opengis.ai.port.LlmException;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolResult;
import org.opengis.tool.api.ToolStatus;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.runtime.ToolRuntime;
import tools.jackson.databind.ObjectMapper;

/** Executes exactly one provider turn and its locally settled tool calls. */
public final class TurnRunner {
  private final LlmClient llm;
  private final ToolRuntime tools;
  private final ObjectMapper mapper;
  private final RetryPolicy retryPolicy;

  public TurnRunner(
      LlmClient llm, ToolRuntime tools, ObjectMapper mapper, RetryPolicy retryPolicy) {
    this.llm = llm;
    this.tools = tools;
    this.mapper = mapper;
    this.retryPolicy = retryPolicy;
  }

  public ProviderTurn provider(CanonicalRequest request, AgentRunContext context) {
    context.cancellation().throwIfCancelled();
    Instant started = Instant.now();
    try {
      LlmResponse response =
          retryPolicy.execute(
              () ->
                  llm.complete(
                      request.toLlmRequest(),
                      chunk -> emitChunk(context, chunk),
                      context.cancellation()),
              context.cancellation());
      long duration = java.time.Duration.between(started, Instant.now()).toMillis();
      context.emit(
          "agent.provider.completed",
          Map.of(
              "duration_ms", duration,
              "finish_reason", response.finishReason(),
              "tool_call_count", response.toolCalls().size()));
      return new ProviderTurn(response, duration);
    } catch (LlmException exception) {
      StopReason reason =
          "provider_timeout".equals(exception.error().code())
              ? StopReason.PROVIDER_TIMEOUT
              : "llm_cancelled".equals(exception.error().code())
                  ? StopReason.CANCELLED
                  : StopReason.ERROR;
      throw new AgentStopException(reason, exception.getMessage(), exception);
    }
  }

  public List<ToolSettlement> settle(
      List<LlmToolCall> calls, AgentRunContext context, RuntimeControl control) {
    List<ToolSettlement> settlements = new ArrayList<>();
    for (LlmToolCall call : calls) {
      control.beforeTool();
      Instant started = Instant.now();
      ToolResult result = executeWithTimeout(call, context, control);
      long duration = java.time.Duration.between(started, Instant.now()).toMillis();
      settlements.add(new ToolSettlement(call, result, duration));
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
    return List.copyOf(settlements);
  }

  private ToolResult executeWithTimeout(
      LlmToolCall call, AgentRunContext context, RuntimeControl control) {
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

  private static void emitChunk(AgentRunContext context, LlmChunk chunk) {
    context.emit(
        "agent.provider.chunk",
        Map.ofEntries(
            Map.entry("kind", chunk.kind().name().toLowerCase(java.util.Locale.ROOT)),
            Map.entry("text", chunk.text()),
            Map.entry("tool_index", chunk.toolIndex()),
            Map.entry("tool_call_id", chunk.toolCallId()),
            Map.entry("tool_name", chunk.toolName()),
            Map.entry("arguments_delta", chunk.argumentsDelta()),
            Map.entry("finish_reason", chunk.finishReason())));
  }

  public String toolResultContent(ToolResult result) {
    return mapper.writeValueAsString(result);
  }
}
