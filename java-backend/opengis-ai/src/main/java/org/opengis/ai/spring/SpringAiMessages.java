package org.opengis.ai.spring;

import java.util.ArrayList;
import java.util.List;
import org.opengis.ai.model.LlmMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Lossless message projection between the stable OpenGIS model and Spring AI. */
public final class SpringAiMessages {
  private SpringAiMessages() {}

  public static List<Message> fromOpenGis(List<LlmMessage> source) {
    List<Message> messages = new ArrayList<>();
    for (LlmMessage message : source) {
      switch (message.role()) {
        case SYSTEM -> messages.add(new SystemMessage(message.content()));
        case USER -> messages.add(new UserMessage(message.content()));
        case ASSISTANT ->
            messages.add(
                AssistantMessage.builder()
                    .content(message.content())
                    .toolCalls(
                        message.toolCalls().stream()
                            .map(
                                call ->
                                    new AssistantMessage.ToolCall(
                                        call.id(),
                                        "function",
                                        call.name(),
                                        call.arguments().toString()))
                            .toList())
                    .build());
        case TOOL ->
            messages.add(
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse(
                                message.toolCallId(), message.name(), message.content())))
                    .build());
      }
    }
    return List.copyOf(messages);
  }
}
