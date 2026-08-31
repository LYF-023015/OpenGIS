/** 文件职责：ai 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.model.context;

import org.opengis.assistant.model.context.PromptSection.PromptCachePolicy;
import org.opengis.assistant.model.context.PromptSection.PromptSectionKind;
import org.opengis.assistant.model.context.PromptSection.PromptStability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmRole;
import org.opengis.assistant.model.LlmToolCall;
import org.opengis.assistant.model.LlmToolDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RequestCompactorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void keepsMoreThanEightHistoryMessagesWhenTheyFitTheTokenBudget() {
    List<LlmMessage> history =
        java.util.stream.IntStream.range(0, 24)
            .mapToObj(index -> LlmMessage.user("short message " + index))
            .toList();

    CanonicalRequest compacted =
        new RequestCompactor(new TokenEstimator(mapper)).compact(request(history), 128_000);

    assertThat(compacted.sections())
        .filteredOn(section -> section.kind() == PromptSectionKind.HISTORY)
        .singleElement()
        .extracting(PromptSection::messages)
        .asList()
        .hasSize(24);
  }

  @Test
  void compactsAtHighPressureBeforeTheRequestOverflows() {
    List<LlmMessage> history =
        java.util.stream.IntStream.range(0, 12)
            .mapToObj(index -> LlmMessage.user("history-" + index + " " + "x".repeat(900)))
            .toList();
    CanonicalRequest original = request(history);
    TokenEstimator estimator = new TokenEstimator(mapper);
    RequestBudget budget = RequestBudget.evaluate(original, estimator, 3200);

    CanonicalRequest compacted = new RequestCompactor(estimator).compact(original, 3200);

    assertThat(budget.pressure()).isEqualTo(RequestBudget.Pressure.HIGH);
    assertThat(compacted.messages().size()).isLessThan(original.messages().size());
  }

  @Test
  void compactionNeverStartsHistoryWithOrphanToolMessage() {
    ObjectNode bigArgs = mapper.createObjectNode();
    bigArgs.put("payload", "b".repeat(10_000));
    List<LlmMessage> history =
        List.of(
            LlmMessage.user("a".repeat(10_000)),
            LlmMessage.assistant("", List.of(new LlmToolCall("call-1", "read_file", bigArgs))),
            LlmMessage.tool("call-1", "read_file", "ok"),
            LlmMessage.user("new"));

    CanonicalRequest compacted =
        new RequestCompactor(new TokenEstimator(mapper)).compact(request(history), 4000);

    List<LlmMessage> dialogue =
        compacted.messages().stream().filter(message -> message.role() != LlmRole.SYSTEM).toList();
    assertThat(dialogue).isNotEmpty();
    assertThat(dialogue.getFirst().role()).isNotEqualTo(LlmRole.TOOL);
    assertThat(dialogue.getLast().role()).isEqualTo(LlmRole.USER);
    assertThat(dialogue.getLast().content()).isEqualTo("new");
  }

  @Test
  void compactionKeepsCompleteToolTurnWhenItFits() {
    List<LlmMessage> history =
        List.of(
            LlmMessage.user("a".repeat(10_000)),
            LlmMessage.assistant(
                "", List.of(new LlmToolCall("call-1", "read_file", mapper.createObjectNode()))),
            LlmMessage.tool("call-1", "read_file", "ok"),
            LlmMessage.user("new"));

    CanonicalRequest compacted =
        new RequestCompactor(new TokenEstimator(mapper)).compact(request(history), 2000);

    List<LlmMessage> dialogue =
        compacted.messages().stream().filter(message -> message.role() != LlmRole.SYSTEM).toList();
    assertThat(dialogue.getFirst().role()).isEqualTo(LlmRole.ASSISTANT);
    assertThat(dialogue.getFirst().toolCalls()).hasSize(1);
    assertThat(dialogue.get(1).role()).isEqualTo(LlmRole.TOOL);
    assertThat(dialogue.get(1).toolCallId()).isEqualTo("call-1");
  }

  private CanonicalRequest request(List<LlmMessage> history) {
    return new CanonicalRequestBuilder("model")
        .add(
            "system",
            PromptSectionKind.SYSTEM,
            List.of(LlmMessage.system("stable")),
            PromptStability.STATIC,
            PromptCachePolicy.CACHEABLE)
        .add(
            "history",
            PromptSectionKind.HISTORY,
            history,
            PromptStability.SESSION_STATIC,
            PromptCachePolicy.NONE)
        .tools(
            List.of(
                new LlmToolDefinition(
                    "read", "Read", mapper.createObjectNode().put("type", "object"))))
        .options(0.2, 100, Duration.ofSeconds(2), Map.of())
        .build();
  }
}
