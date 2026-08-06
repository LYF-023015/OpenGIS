package org.opengis.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.ai.context.CanonicalRequest;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmRole;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.model.LlmToolDefinition;
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
