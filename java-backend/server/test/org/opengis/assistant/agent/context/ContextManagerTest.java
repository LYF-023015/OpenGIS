/** 文件职责：agent 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.assistant.model.context.CanonicalRequest;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmRole;
import org.opengis.assistant.model.LlmToolCall;
import org.opengis.assistant.model.LlmToolDefinition;
import org.opengis.assistant.memory.MemoryKind;
import org.opengis.assistant.memory.MemoryRepository;
import tools.jackson.databind.ObjectMapper;

class ContextManagerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void danglingAssistantToolCallIsDroppedBeforeProviderRequest(@TempDir Path workspace) {
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("load the data"));
    contexts.append(
        "conversation",
        LlmMessage.assistant(
            "", List.of(new LlmToolCall("call-1", "read_file", mapper.createObjectNode()))));
    contexts.append("conversation", LlmMessage.user("continue"));

    CanonicalRequest request = request(contexts);

    assertThat(request.messages())
        .noneMatch(
            message -> message.role() == LlmRole.ASSISTANT && !message.toolCalls().isEmpty());
    assertThat(request.messages())
        .filteredOn(message -> message.role() != LlmRole.SYSTEM)
        .extracting(LlmMessage::role)
        .containsExactly(LlmRole.USER, LlmRole.USER);
  }

  @Test
  void completedToolTurnSurvivesRepair(@TempDir Path workspace) {
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("load the data"));
    contexts.append(
        "conversation",
        LlmMessage.assistant(
            "", List.of(new LlmToolCall("call-1", "read_file", mapper.createObjectNode()))));
    contexts.append("conversation", LlmMessage.tool("call-1", "read_file", "{\"ok\":true}"));
    contexts.append("conversation", LlmMessage.assistant("Done", List.of()));

    CanonicalRequest request = request(contexts);

    assertThat(request.messages())
        .filteredOn(message -> message.role() != LlmRole.SYSTEM)
        .hasSize(4);
    assertThat(request.messages()).extracting(LlmMessage::toolCallId).contains("call-1");
  }

  @Test
  void partialToolTurnIsDropped(@TempDir Path workspace) {
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("load the data"));
    contexts.append(
        "conversation",
        LlmMessage.assistant(
            "",
            List.of(
                new LlmToolCall("call-1", "read_file", mapper.createObjectNode()),
                new LlmToolCall("call-2", "read_file", mapper.createObjectNode()))));
    contexts.append("conversation", LlmMessage.tool("call-1", "read_file", "{\"ok\":true}"));
    contexts.append("conversation", LlmMessage.user("continue"));

    CanonicalRequest request = request(contexts);

    assertThat(request.messages())
        .noneMatch(
            message -> message.role() == LlmRole.ASSISTANT && !message.toolCalls().isEmpty());
    assertThat(request.messages())
        .filteredOn(message -> message.role() != LlmRole.SYSTEM)
        .extracting(LlmMessage::role)
        .containsExactly(LlmRole.USER, LlmRole.USER);
  }

  @Test
  void danglingToolMessageWithoutAssistantIsDropped(@TempDir Path workspace) {
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("load the data"));
    contexts.append("conversation", LlmMessage.tool("call-1", "read_file", "{\"ok\":true}"));

    CanonicalRequest request = request(contexts);

    assertThat(request.messages()).noneMatch(message -> message.role() == LlmRole.TOOL);
    assertThat(request.messages())
        .filteredOn(message -> message.role() != LlmRole.SYSTEM)
        .extracting(LlmMessage::role)
        .containsExactly(LlmRole.USER);
  }

  @Test
  void injectsOnlyRelevantMemoryWithinThePromptBudget(@TempDir Path workspace) {
    MemoryRepository memory = new MemoryRepository(workspace);
    memory.add(MemoryKind.FACT, "项目道路数据使用 EPSG:4326 坐标系", "test", java.util.Map.of());
    memory.add(MemoryKind.FACT, "办公室咖啡机每天清洗", "test", java.util.Map.of());
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("道路数据的 EPSG 坐标系是什么？"));

    CanonicalRequest request = request(contexts);

    String prompt =
        request.messages().stream()
            .map(LlmMessage::content)
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThat(prompt).contains("EPSG:4326").doesNotContain("咖啡机");
  }

  @Test
  void persistsSemanticSummaryAndRemovesSummarizedPrefixFromActiveHistory(@TempDir Path workspace) {
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("使用 EPSG:4326"));
    contexts.append("conversation", LlmMessage.assistant("已确认坐标系", List.of()));
    contexts.append("conversation", LlmMessage.user("继续检查建筑物图层"));

    ContextManager.SummaryBatch batch = contexts.summaryBatch("conversation", 2);
    contexts.applySummary("conversation", "项目坐标系为 EPSG:4326。", batch.summarizedThrough());

    ContextManager reloaded = new ContextManager(workspace);
    CanonicalRequest request = request(reloaded);
    String prompt =
        request.messages().stream()
            .map(LlmMessage::content)
            .collect(java.util.stream.Collectors.joining("\n"));

    assertThat(batch.messages()).hasSize(2);
    assertThat(prompt)
        .contains("Conversation summary", "项目坐标系为 EPSG:4326", "继续检查建筑物图层")
        .doesNotContain("已确认坐标系");
  }

  @Test
  void summaryCheckpointNeverLeavesToolResultAtTheStartOfHistory(@TempDir Path workspace) {
    ContextManager contexts = new ContextManager(workspace);
    contexts.append("conversation", LlmMessage.user("读取文件"));
    contexts.append(
        "conversation",
        LlmMessage.assistant(
            "", List.of(new LlmToolCall("call-1", "read_file", mapper.createObjectNode()))));
    contexts.append("conversation", LlmMessage.tool("call-1", "read_file", "EPSG:4326"));
    contexts.append("conversation", LlmMessage.assistant("读取完成", List.of()));
    contexts.append("conversation", LlmMessage.user("继续"));

    ContextManager.SummaryBatch batch = contexts.summaryBatch("conversation", 2);
    contexts.applySummary("conversation", "已读取空间数据。", batch.summarizedThrough());

    List<LlmMessage> dialogue =
        request(contexts).messages().stream()
            .filter(message -> message.role() != LlmRole.SYSTEM)
            .toList();
    assertThat(batch.messages()).hasSize(3);
    assertThat(dialogue).isNotEmpty();
    assertThat(dialogue.getFirst().role()).isNotEqualTo(LlmRole.TOOL);
  }

  private CanonicalRequest request(ContextManager contexts) {
    return contexts.buildRequest(
        "conversation",
        "test-model",
        "system",
        "capabilities",
        "tool-protocol",
        "",
        List.<LlmToolDefinition>of(),
        0.2,
        128,
        Duration.ofSeconds(5));
  }
}
