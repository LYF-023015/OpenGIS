package org.opengis.agent.loop;

import java.util.List;
import java.util.Map;
import org.opengis.agent.context.AgentRunContext;
import org.opengis.agent.context.ContextManager;
import org.opengis.agent.execution.ToolSchemaProjector;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.context.CanonicalRequest;
import org.opengis.ai.context.RequestCompactor;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmToolDefinition;
import org.opengis.ai.model.LlmUsage;
import org.opengis.tool.registry.ToolRegistry;

/** Function-call-first agent loop: provider turn, local settlement, provider turn. */
public final class LoopKernel {
  private final ContextManager contexts;
  private final ToolRegistry tools;
  private final ToolSchemaProjector schemas;
  private final RequestCompactor compactor;
  private final CacheObservatory cache;
  private final TurnRunner turns;
  private final ContinuationPolicy continuation = new ContinuationPolicy();

  public LoopKernel(
      ContextManager contexts,
      ToolRegistry tools,
      ToolSchemaProjector schemas,
      RequestCompactor compactor,
      CacheObservatory cache,
      TurnRunner turns) {
    this.contexts = contexts;
    this.tools = tools;
    this.schemas = schemas;
    this.compactor = compactor;
    this.cache = cache;
    this.turns = turns;
  }

  public AgentLoopResult run(AgentLoopRequest request, AgentRunContext context) {
    RuntimeControl control =
        new RuntimeControl(
            context.profile(),
            request.maxRuntime(),
            request.toolTimeout(),
            3,
            context.cancellation());
    LlmUsage usage = LlmUsage.EMPTY;
    String finalAnswer = "";
    int nudges = 0;
    try {
      contexts.append(context.conversationId(), LlmMessage.user(request.prompt()));
      List<LlmToolDefinition> providerTools = schemas.project(tools, context.profile());
      while (true) {
        control.beforeProviderTurn();
        CanonicalRequest canonical =
            contexts.buildRequest(
                context.conversationId(),
                request.model(),
                request.systemPrompt() + context.profile().promptSuffix(),
                request.capabilityManifest(),
                request.toolProtocol(),
                request.userPreferences(),
                providerTools,
                request.temperature(),
                request.maxTokens(),
                request.providerTimeout());
        canonical = compactor.compact(canonical, request.contextWindow());
        context.emit(
            "agent.turn.started",
            Map.of(
                "iteration", control.providerTurns(),
                "messages", canonical.messages().size(),
                "tools", canonical.tools().size()));
        ProviderTurn turn = turns.provider(canonical, context);
        LlmResponse response = turn.response();
        usage = usage.plus(response.usage());
        cache.record(request.providerId(), canonical, response.usage());
        contexts.append(
            context.conversationId(),
            LlmMessage.assistant(response.content(), response.toolCalls()));
        if (!response.hasToolCalls()) {
          finalAnswer = response.content();
          if ((finalAnswer.isBlank() || continuation.isDeviation(finalAnswer)) && nudges == 0) {
            nudges++;
            contexts.append(
                context.conversationId(),
                LlmMessage.user(
                    "[Runtime control] Continue by calling the required tool now, or give a clear final answer if the task is complete."));
            continue;
          }
          if (continuation.isDeviation(finalAnswer)) {
            throw new AgentStopException(
                StopReason.DEVIATION, "Model repeatedly promised work without a tool call");
          }
          return finish(
              "completed", StopReason.COMPLETED, finalAnswer, "", control, usage, context);
        }
        List<ToolSettlement> settlements = turns.settle(response.toolCalls(), context, control);
        for (ToolSettlement settlement : settlements) {
          contexts.append(
              context.conversationId(),
              LlmMessage.tool(
                  settlement.call().id(),
                  settlement.call().name(),
                  turns.toolResultContent(settlement.result())));
        }
      }
    } catch (AgentStopException exception) {
      String status = exception.reason() == StopReason.CANCELLED ? "cancelled" : "error";
      return finish(
          status, exception.reason(), finalAnswer, exception.getMessage(), control, usage, context);
    } catch (RuntimeException exception) {
      return finish(
          "error",
          StopReason.ERROR,
          finalAnswer,
          exception.getMessage() == null
              ? exception.getClass().getSimpleName()
              : exception.getMessage(),
          control,
          usage,
          context);
    }
  }

  private static AgentLoopResult finish(
      String status,
      StopReason reason,
      String answer,
      String error,
      RuntimeControl control,
      LlmUsage usage,
      AgentRunContext context) {
    context.emit(
        "agent.run.finished",
        Map.of(
            "status", status,
            "stop_reason", reason.name().toLowerCase(java.util.Locale.ROOT),
            "provider_turns", control.providerTurns(),
            "tool_steps", control.toolSteps(),
            "error", error == null ? "" : error));
    return new AgentLoopResult(
        status,
        reason,
        answer == null ? "" : answer,
        error == null ? "" : error,
        control.providerTurns(),
        control.toolSteps(),
        usage);
  }
}
