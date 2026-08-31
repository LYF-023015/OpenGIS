/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.spring;

import java.util.List;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmUsage;
import org.opengis.assistant.provider.spring.SpringAiMessages;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Tool-free semantic summarization for conversation prefixes removed from the active window. */
final class ConversationSummarizer {
  private static final String INSTRUCTIONS =
      """
      You maintain a durable summary of earlier OpenGIS conversation turns.
      Merge the existing summary with the newly compacted messages into one concise summary.
      Preserve only information needed to continue future work: user goals and preferences,
      confirmed GIS facts and identifiers, decisions, completed actions, exact artifact paths,
      unresolved tasks, and important tool errors. Preserve exact CRS codes, field names, layer
      names, file paths, numeric values, and constraints. Do not invent facts or claim unfinished
      work is complete. Remove greetings, repetition, transient progress text, and raw logs.
      Use short headings and bullets. Return only the updated summary in the conversation language.
      """;

  private final ChatModel model;

  ConversationSummarizer(ChatModel model) {
    this.model = java.util.Objects.requireNonNull(model, "model");
  }

  Result summarize(
      String modelName,
      String existingSummary,
      List<LlmMessage> compactedMessages,
      int requestedOutputTokens) {
    if (compactedMessages == null || compactedMessages.isEmpty()) {
      return new Result(existingSummary == null ? "" : existingSummary, LlmUsage.EMPTY);
    }
    String promptText =
        "Existing summary:\n"
            + blankAsNone(existingSummary)
            + "\n\nNewly compacted messages:\n"
            + transcript(compactedMessages);
    ToolCallingChatOptions options =
        ToolCallingChatOptions.builder()
            .model(modelName)
            .temperature(0.1)
            .maxTokens(Math.min(2_048, Math.max(512, requestedOutputTokens)))
            .build();
    ChatResponse response =
        model.call(
            new Prompt(
                SpringAiMessages.fromOpenGis(
                    List.of(LlmMessage.system(INSTRUCTIONS), LlmMessage.user(promptText))),
                options));
    if (response == null || response.getResult() == null || response.hasToolCalls()) {
      throw new IllegalStateException("Conversation summarizer returned no plain-text summary");
    }
    String summary = response.getResult().getOutput().getText();
    if (summary == null || summary.isBlank()) {
      throw new IllegalStateException("Conversation summarizer returned an empty summary");
    }
    Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    return new Result(summary.strip(), toUsage(usage));
  }

  private static String transcript(List<LlmMessage> messages) {
    StringBuilder value = new StringBuilder();
    for (LlmMessage message : messages) {
      value.append('[').append(message.role()).append(']');
      if (!message.name().isBlank()) {
        value.append(' ').append(message.name());
      }
      value.append('\n');
      if (!message.content().isBlank()) {
        value.append(message.content()).append('\n');
      }
      if (!message.toolCalls().isEmpty()) {
        message
            .toolCalls()
            .forEach(
                call ->
                    value
                        .append("tool_call ")
                        .append(call.name())
                        .append(' ')
                        .append(call.arguments())
                        .append('\n'));
      }
      value.append('\n');
    }
    return value.toString();
  }

  private static String blankAsNone(String value) {
    return value == null || value.isBlank() ? "(none)" : value;
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

  record Result(String summary, LlmUsage usage) {}
}
