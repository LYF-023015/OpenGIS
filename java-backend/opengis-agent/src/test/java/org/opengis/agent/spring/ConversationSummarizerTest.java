package org.opengis.agent.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.opengis.ai.model.LlmMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

class ConversationSummarizerTest {
  @Test
  void mergesExistingSummaryWithCompactedMessagesWithoutTools() {
    AtomicReference<Prompt> captured = new AtomicReference<>();
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            captured.set(prompt);
            return new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "## 已确认\n- 项目使用 EPSG:4326\n- 输出文件为 output/buildings.geojson"))));
          }

          @Override
          public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
          }
        };

    ConversationSummarizer.Result result =
        new ConversationSummarizer(model)
            .summarize(
                "test-model",
                "用户要求中文回答。",
                List.of(
                    LlmMessage.user("检查建筑物图层"),
                    LlmMessage.assistant(
                        "输出已保存到 output/buildings.geojson，坐标系为 EPSG:4326。", List.of())),
                1_024);

    String providerPrompt =
        captured.get().getInstructions().stream()
            .map(message -> message.getText())
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThat(result.summary()).contains("EPSG:4326", "output/buildings.geojson");
    assertThat(providerPrompt).contains("用户要求中文回答", "检查建筑物图层", "output/buildings.geojson");
    assertThat(captured.get().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
    assertThat(((ToolCallingChatOptions) captured.get().getOptions()).getToolCallbacks())
        .isNullOrEmpty();
  }
}
