package org.opengis.agent.spring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.opengis.agent.context.AgentRunContext;
import org.opengis.agent.context.ContextManager;
import org.opengis.agent.loop.RuntimeControl;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmUsage;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/** Emits OpenGIS lifecycle events around each Spring AI provider stream. */
public final class RunLifecycleAdvisor implements StreamAdvisor {
  private final RuntimeControl control;
  private final AgentRunContext context;
  private final ContextManager contexts;
  private final AtomicReference<LlmUsage> usage = new AtomicReference<>(LlmUsage.EMPTY);
  private final AtomicReference<String> finalAnswer = new AtomicReference<>("");

  public RunLifecycleAdvisor(
      RuntimeControl control, AgentRunContext context, ContextManager contexts) {
    this.control = control;
    this.context = context;
    this.contexts = contexts;
  }

  @Override
  public String getName() {
    return "opengis-run-lifecycle";
  }

  @Override
  public int getOrder() {
    return ToolCallingAdvisor.DEFAULT_ORDER + 100;
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain chain) {
    beforeTurn(request);
    Instant started = Instant.now();
    Flux<ChatClientResponse> stream = chain.nextStream(request).doOnNext(this::emitChunk);
    return new ChatClientMessageAggregator()
        .aggregateChatClientResponse(stream, response -> completeTurn(response, started));
  }

  private void beforeTurn(ChatClientRequest request) {
    control.beforeProviderTurn();
    int toolCount =
        request.prompt().getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null
            ? options.getToolCallbacks().size()
            : 0;
    context.emit(
        "agent.turn.started",
        Map.of(
            "iteration", control.providerTurns(),
            "messages", request.prompt().getInstructions().size(),
            "tools", toolCount));
  }

  private void emitChunk(ChatClientResponse clientResponse) {
    ChatResponse response = clientResponse == null ? null : clientResponse.chatResponse();
    if (response == null || response.getResult() == null) {
      return;
    }
    AssistantMessage output = response.getResult().getOutput();
    if (output != null && output.getText() != null && !output.getText().isEmpty()) {
      emit("text", output.getText(), "", "", "");
    }
    if (output != null) {
      for (AssistantMessage.ToolCall call : output.getToolCalls()) {
        emit("tool", "", call.id(), call.name(), call.arguments());
      }
    }
  }

  private void emit(String kind, String text, String callId, String name, String arguments) {
    context.emit(
        "agent.provider.chunk",
        Map.of(
            "kind", kind,
            "text", text == null ? "" : text,
            "tool_call_id", callId == null ? "" : callId,
            "tool_name", name == null ? "" : name,
            "arguments_delta", arguments == null ? "" : arguments));
  }

  private void completeTurn(ChatClientResponse clientResponse, Instant started) {
    ChatResponse response = clientResponse == null ? null : clientResponse.chatResponse();
    if (response == null || response.getResult() == null) {
      return;
    }
    Usage providerUsage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    if (providerUsage != null) {
      usage.updateAndGet(current -> current.plus(toUsage(providerUsage)));
    }
    if (!response.hasToolCalls()) {
      String content = response.getResult().getOutput().getText();
      finalAnswer.set(content == null ? "" : content);
      contexts.append(context.conversationId(), LlmMessage.assistant(finalAnswer.get(), List.of()));
    }
    context.emit(
        "agent.provider.completed",
        Map.of(
            "duration_ms", java.time.Duration.between(started, Instant.now()).toMillis(),
            "finish_reason", response.getResult().getMetadata().getFinishReason(),
            "tool_call_count", response.getResult().getOutput().getToolCalls().size()));
  }

  private static LlmUsage toUsage(Usage value) {
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

  public LlmUsage usage() {
    return usage.get();
  }

  public String finalAnswer() {
    return finalAnswer.get();
  }
}
