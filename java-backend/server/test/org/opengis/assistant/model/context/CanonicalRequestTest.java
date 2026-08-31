/** 文件职责：ai 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.model.context;

import org.opengis.assistant.model.context.PromptSection.PromptCachePolicy;
import org.opengis.assistant.model.context.PromptSection.PromptSectionKind;
import org.opengis.assistant.model.context.PromptSection.PromptStability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmToolDefinition;
import org.opengis.assistant.model.LlmUsage;
import tools.jackson.databind.ObjectMapper;

class CanonicalRequestTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final TokenEstimator estimator = new TokenEstimator(mapper);

  @Test
  void tokenEstimatorTreatsCjkTextMoreConservativelyThanAsciiText() {
    int ascii = estimator.messages(List.of(LlmMessage.user("a".repeat(400))));
    int cjk = estimator.messages(List.of(LlmMessage.user("中".repeat(400))));

    assertThat(cjk).isGreaterThan(ascii * 3);
  }

  @Test
  void stablePrefixIgnoresAppendOnlyHistory() {
    CanonicalRequest first = request(List.of(LlmMessage.user("one")));
    CanonicalRequest second =
        request(List.of(LlmMessage.user("one"), LlmMessage.assistant("two", List.of())));

    assertThat(first.stableSystemHash(mapper)).isEqualTo(second.stableSystemHash(mapper));
    assertThat(first.cacheablePrefixHash(mapper)).isEqualTo(second.cacheablePrefixHash(mapper));
  }

  @Test
  void budgetIncludesToolSchemasAndCompactionPreservesStableSections() {
    List<LlmMessage> history = new ArrayList<>();
    for (int index = 0; index < 30; index++) {
      history.add(LlmMessage.user("long history ".repeat(100)));
    }
    CanonicalRequest original = request(history);
    RequestBudget budget = RequestBudget.evaluate(original, estimator, 1000);
    CanonicalRequest compacted = new RequestCompactor(estimator).compact(original, 1000);

    assertThat(budget.toolSchemaTokens()).isPositive();
    assertThat(budget.pressure()).isEqualTo(RequestBudget.Pressure.OVERFLOW);
    assertThat(compacted.messages().size()).isLessThan(original.messages().size());
    assertThat(compacted.sections().getFirst().id()).isEqualTo("system");
    assertThat(compacted.tools()).isEqualTo(original.tools());
  }

  @Test
  void cacheObservatoryTracksUsageAndPrefixChangesWithoutPromptContent() {
    CacheObservatory observatory = new CacheObservatory(mapper);
    observatory.record(
        "openai", request(List.of(LlmMessage.user("one"))), new LlmUsage(100, 10, 80, 80, 0));
    observatory.record(
        "openai", request(List.of(LlmMessage.user("two"))), new LlmUsage(100, 10, 70, 70, 0));

    assertThat(observatory.snapshot()).containsEntry("requests", 2L);
    assertThat(observatory.snapshot()).containsEntry("cached_tokens", 150L);
    assertThat(observatory.snapshot()).containsEntry("system_prefix_changes", 0L);
    assertThat(observatory.snapshot().toString()).doesNotContain("one", "two", "secret");
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
