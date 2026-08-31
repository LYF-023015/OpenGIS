/** 文件职责：agent 后端领域：承载该领域的核心业务流程。 */
package org.opengis.assistant.agent.spring;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.opengis.assistant.agent.context.AgentRunContext;
import org.opengis.assistant.agent.context.ContextManager;
import org.opengis.assistant.agent.execution.ToolSchemaProjector;
import org.opengis.assistant.agent.loop.AgentLoopRequest;
import org.opengis.assistant.agent.loop.AgentLoopResult;
import org.opengis.assistant.agent.loop.AgentStopException;
import org.opengis.assistant.agent.loop.ContinuationPolicy;
import org.opengis.assistant.agent.loop.RuntimeControl;
import org.opengis.assistant.agent.loop.StopReason;
import org.opengis.assistant.model.context.CacheObservatory;
import org.opengis.assistant.model.context.CanonicalRequest;
import org.opengis.assistant.model.context.RequestCompactor;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmToolDefinition;
import org.opengis.assistant.model.LlmUsage;
import org.opengis.assistant.provider.spring.DefinitionOnlyToolCallback;
import org.opengis.assistant.provider.spring.SpringAiMessages;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

/** Spring AI ChatClient orchestration with the OpenGIS runtime as its policy boundary. */
public final class SpringAiAgentRunner {
  private final ContextManager contexts;
  private final ToolRegistry tools;
  private final ToolSchemaProjector schemas;
  private final RequestCompactor compactor;
  private final CacheObservatory cache;
  private final ChatModel model;
  private final ConversationSummarizer summarizer;
  private final ToolRuntime toolRuntime;
  private final ObjectMapper mapper;
  private final ContinuationPolicy continuation = new ContinuationPolicy();

  public SpringAiAgentRunner(
      ContextManager contexts,
      ToolRegistry tools,
      ToolSchemaProjector schemas,
      RequestCompactor compactor,
      CacheObservatory cache,
      ChatModel model,
      ToolRuntime toolRuntime,
      ObjectMapper mapper) {
    this.contexts = contexts;
    this.tools = tools;
    this.schemas = schemas;
    this.compactor = compactor;
    this.cache = cache;
    this.model = model;
    this.summarizer = new ConversationSummarizer(model);
    this.toolRuntime = toolRuntime;
    this.mapper = mapper;
  }

  public AgentLoopResult run(AgentLoopRequest request, AgentRunContext context) {
    RuntimeControl control =
        new RuntimeControl(
            context.profile(),
            request.maxRuntime(),
            request.toolTimeout(),
            3,
            context.cancellation());
    RunLifecycleAdvisor lifecycle = new RunLifecycleAdvisor(control, context, contexts);
    String finalAnswer = "";
    LlmUsage summaryUsage = LlmUsage.EMPTY;
    try {
      contexts.append(context.conversationId(), LlmMessage.user(request.prompt()));
      List<LlmToolDefinition> providerTools = schemas.project(tools, context.profile());
      List<ToolCallback> callbacks =
          providerTools.stream()
              .map(DefinitionOnlyToolCallback::new)
              .map(ToolCallback.class::cast)
              .toList();
      for (int attempt = 0; attempt < 2; attempt++) {
        PreparedRequest prepared = prepareRequest(request, context, providerTools);
        CanonicalRequest canonical = prepared.request();
        summaryUsage = summaryUsage.plus(prepared.summaryUsage());
        runChatClient(request, context, control, lifecycle, canonical, callbacks);
        finalAnswer = lifecycle.finalAnswer();
        if (!finalAnswer.isBlank() && !continuation.isDeviation(finalAnswer)) {
          cache.record(request.providerId(), canonical, lifecycle.usage());
          return finish(
              "completed",
              StopReason.COMPLETED,
              finalAnswer,
              "",
              control,
              lifecycle.usage().plus(summaryUsage),
              context);
        }
        if (attempt == 0) {
          contexts.append(
              context.conversationId(),
              LlmMessage.user(
                  "[Runtime control] Continue by calling the required tool now, or give a clear final answer if the task is complete."));
        }
      }
      throw new AgentStopException(
          StopReason.DEVIATION, "Model repeatedly promised work without a tool call");
    } catch (AgentStopException exception) {
      return finishStoppedRun(
          request,
          context,
          control,
          lifecycle.usage().plus(summaryUsage),
          exception.reason(),
          exception.getMessage());
    } catch (RuntimeException exception) {
      AgentStopException controlled = findControlled(exception);
      if (controlled != null) {
        return finishStoppedRun(
            request,
            context,
            control,
            lifecycle.usage().plus(summaryUsage),
            controlled.reason(),
            controlled.getMessage());
      }
      String error =
          exception.getMessage() == null
              ? exception.getClass().getSimpleName()
              : exception.getMessage();
      String fallback = fallbackAnswer(StopReason.ERROR, error);
      publishFinalAnswer(context, fallback, "runtime-fallback");
      return finish(
          "error",
          StopReason.ERROR,
          fallback,
          error,
          control,
          lifecycle.usage().plus(summaryUsage),
          context);
    }
  }

  private AgentLoopResult finishStoppedRun(
      AgentLoopRequest request,
      AgentRunContext context,
      RuntimeControl control,
      LlmUsage currentUsage,
      StopReason reason,
      String error) {
    String answer = "";
    LlmUsage usage = currentUsage;
    if (canUseModelForFinalization(reason, context)) {
      try {
        Finalization finalization = finalizeWithoutTools(request, context, control, reason, error);
        answer = finalization.answer();
        usage = usage.plus(finalization.usage());
      } catch (RuntimeException ignored) {
        // A bounded run must still settle with a deterministic response if the provider is down.
      }
    }
    if (answer.isBlank()) {
      answer = fallbackAnswer(reason, error);
      publishFinalAnswer(context, answer, "runtime-fallback");
    }
    String status = reason == StopReason.CANCELLED ? "cancelled" : "error";
    return finish(status, reason, answer, error, control, usage, context);
  }

  private Finalization finalizeWithoutTools(
      AgentLoopRequest request,
      AgentRunContext context,
      RuntimeControl control,
      StopReason reason,
      String error) {
    contexts.append(
        context.conversationId(),
        LlmMessage.system(
            "[Runtime finalization] The run stopped because "
                + reason.name()
                + ": "
                + (error == null ? "" : error)
                + ". No tools are available now. Give the user a final response based only on "
                + "the conversation and completed tool results. Clearly separate completed work, "
                + "unfinished work, and the recommended next step. Never claim unfinished work was completed."));
    CanonicalRequest canonical =
        contexts.buildRequest(
            context.conversationId(),
            request.model(),
            request.systemPrompt() + context.profile().promptSuffix(),
            "Finalization only; no tools are available.",
            "Do not call tools. Return a concise final response.",
            request.userPreferences(),
            List.of(),
            request.temperature(),
            request.maxTokens(),
            request.providerTimeout());
    canonical = compactor.compact(canonical, request.contextWindow());
    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder()
            .model(request.model())
            .temperature(request.temperature())
            .maxTokens(request.maxTokens())
            .build();
    control.recordFinalizationTurn();
    ChatResponse response =
        model.call(new Prompt(SpringAiMessages.fromOpenGis(canonical.messages()), options));
    if (response == null || response.getResult() == null || response.hasToolCalls()) {
      throw new IllegalStateException("Finalization provider returned no plain-text answer");
    }
    String answer = response.getResult().getOutput().getText();
    if (answer == null || answer.isBlank()) {
      throw new IllegalStateException("Finalization provider returned an empty answer");
    }
    contexts.append(context.conversationId(), LlmMessage.assistant(answer, List.of()));
    publishFinalAnswer(context, answer, "model-finalization");
    Usage providerUsage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    return new Finalization(answer, toUsage(providerUsage));
  }

  private static boolean canUseModelForFinalization(StopReason reason, AgentRunContext context) {
    return !context.cancellation().isCancelled()
        && Set.of(StopReason.STEP_LIMIT, StopReason.REPEATED_FAILURE, StopReason.DEVIATION)
            .contains(reason);
  }

  private void publishFinalAnswer(AgentRunContext context, String answer, String source) {
    if (contexts.messages(context.conversationId()).stream()
        .noneMatch(
            message ->
                message.role() == org.opengis.assistant.model.LlmRole.ASSISTANT
                    && answer.equals(message.content()))) {
      contexts.append(context.conversationId(), LlmMessage.assistant(answer, List.of()));
    }
    context.emit(
        "agent.provider.chunk",
        Map.of(
            "kind", "text",
            "text", answer,
            "tool_call_id", "",
            "tool_name", "",
            "arguments_delta", "",
            "source", source));
  }

  private static String fallbackAnswer(StopReason reason, String error) {
    String detail = error == null || error.isBlank() ? reason.name() : error;
    return switch (reason) {
      case CANCELLED -> "任务已取消。已完成的工具结果和成果文件均已保留。";
      case STEP_LIMIT -> "本次任务已达到模型或工具调用上限，未能继续执行。已完成的步骤和成果均已保留；请基于当前会话继续任务。";
      case REPEATED_FAILURE -> "本次任务因同一工具重复失败而停止。已完成的步骤和成果均已保留；请检查参数或更换执行路径后继续。";
      case TOOL_TIMEOUT, PROVIDER_TIMEOUT -> "本次任务因调用超时而停止。已完成的步骤和成果均已保留；可以稍后从当前会话继续。";
      case DEVIATION -> "模型未能在运行边界内完成所需操作。当前执行记录已保留，请确认后继续任务。";
      default -> "本次任务未能完成。已完成的步骤和成果均已保留。原因：" + detail;
    };
  }

  private static LlmUsage toUsage(Usage value) {
    if (value == null) {
      return LlmUsage.EMPTY;
    }
    return new LlmUsage(
        number(value.getPromptTokens()),
        number(value.getCompletionTokens()),
        number(value.getCacheReadInputTokens()),
        number(value.getCacheReadInputTokens()),
        number(value.getCacheWriteInputTokens()));
  }

  private static long number(Number value) {
    return value == null ? 0 : value.longValue();
  }

  private record Finalization(String answer, LlmUsage usage) {}

  private PreparedRequest prepareRequest(
      AgentLoopRequest request, AgentRunContext context, List<LlmToolDefinition> providerTools) {
    CanonicalRequest canonical = canonicalRequest(request, context, providerTools);
    CanonicalRequest compacted = compactor.compact(canonical, request.contextWindow());
    int removedMessages = compactedMessages(compacted);
    if (removedMessages == 0 || context.cancellation().isCancelled()) {
      return new PreparedRequest(compacted, LlmUsage.EMPTY);
    }
    ContextManager.SummaryBatch batch =
        contexts.summaryBatch(context.conversationId(), removedMessages);
    if (batch.messages().isEmpty()) {
      return new PreparedRequest(compacted, LlmUsage.EMPTY);
    }
    try {
      ConversationSummarizer.Result result =
          summarizer.summarize(
              request.model(), batch.existingSummary(), batch.messages(), request.maxTokens());
      contexts.applySummary(context.conversationId(), result.summary(), batch.summarizedThrough());
      context.emit(
          "agent.context.compacted",
          Map.of(
              "mode", "semantic-summary",
              "summarized_messages", batch.messages().size(),
              "summarized_through", batch.summarizedThrough()));
      CanonicalRequest rebuilt = canonicalRequest(request, context, providerTools);
      return new PreparedRequest(
          compactor.compact(rebuilt, request.contextWindow()), result.usage());
    } catch (RuntimeException exception) {
      context.emit(
          "agent.context.compaction_failed",
          Map.of(
              "mode",
              "semantic-summary",
              "error",
              exception.getMessage() == null
                  ? exception.getClass().getSimpleName()
                  : exception.getMessage()));
      return new PreparedRequest(compacted, LlmUsage.EMPTY);
    }
  }

  private CanonicalRequest canonicalRequest(
      AgentLoopRequest request, AgentRunContext context, List<LlmToolDefinition> providerTools) {
    return contexts.buildRequest(
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
  }

  private static int compactedMessages(CanonicalRequest request) {
    return request.sections().stream()
        .filter(section -> "conversation-summary-auto".equals(section.id()))
        .map(section -> section.metadata().get("compacted_messages"))
        .filter(Number.class::isInstance)
        .map(Number.class::cast)
        .mapToInt(Number::intValue)
        .sum();
  }

  private record PreparedRequest(CanonicalRequest request, LlmUsage summaryUsage) {}

  @SuppressWarnings("try")
  private void runChatClient(
      AgentLoopRequest request,
      AgentRunContext context,
      RuntimeControl control,
      RunLifecycleAdvisor lifecycle,
      CanonicalRequest canonical,
      List<ToolCallback> callbacks) {
    OpenGisToolCallingManager manager =
        new OpenGisToolCallingManager(toolRuntime, control, context, contexts, mapper);
    ToolCallingAdvisor toolAdvisor =
        ToolCallingAdvisor.builder()
            .toolCallingManager(manager)
            .toolExecutionEligibilityChecker(
                response ->
                    response != null
                        && (response.hasToolCalls()
                            || response.hasFinishReasons(Set.of("tool_calls", "tool_use"))))
            .conversationHistoryEnabled(true)
            .build();
    ChatClient client = ChatClient.builder(model).defaultAdvisors(toolAdvisor, lifecycle).build();
    var options =
        ToolCallingChatOptions.builder()
            .model(request.model())
            .temperature(request.temperature())
            .maxTokens(request.maxTokens());
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Disposable subscription =
        client
            .prompt()
            .messages(SpringAiMessages.fromOpenGis(canonical.messages()))
            .options(options)
            .tools(callbacks.toArray())
            .stream()
            .chatClientResponse()
            .doFinally(ignored -> finished.countDown())
            .subscribe(ignored -> {}, failure::set);
    try (AutoCloseable ignored = context.cancellation().onCancel(subscription::dispose)) {
      if (!finished.await(Math.max(1, request.maxRuntime().toMillis()), TimeUnit.MILLISECONDS)) {
        context.cancellation().cancel();
        subscription.dispose();
        throw new AgentStopException(StopReason.CANCELLED, "Agent runtime deadline exceeded");
      }
      if (context.cancellation().isCancelled()) {
        throw new AgentStopException(StopReason.CANCELLED, "Agent run was cancelled");
      }
      if (failure.get() instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (failure.get() != null) {
        throw new AgentStopException(StopReason.ERROR, "Spring AI stream failed", failure.get());
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      context.cancellation().cancel();
      subscription.dispose();
      throw new AgentStopException(StopReason.CANCELLED, "Agent wait was interrupted", exception);
    } catch (AgentStopException exception) {
      throw exception;
    } catch (Exception exception) {
      subscription.dispose();
      throw new AgentStopException(StopReason.ERROR, "Cannot manage Spring AI stream", exception);
    }
  }

  private static AgentStopException findControlled(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof AgentStopException controlled) {
        return controlled;
      }
      current = current.getCause();
    }
    return null;
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
