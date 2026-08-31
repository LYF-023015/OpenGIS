/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model.context;

import org.opengis.assistant.model.context.PromptSection.PromptCachePolicy;
import org.opengis.assistant.model.context.PromptSection.PromptSectionKind;
import org.opengis.assistant.model.context.PromptSection.PromptStability;

import java.util.ArrayList;
import java.util.List;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmRole;

/** Request-aware history compaction which never mutates the stable prefix or tool schemas. */
public final class RequestCompactor {
  private static final double TARGET_USAGE_RATIO = 0.60;
  private final TokenEstimator estimator;

  public RequestCompactor(TokenEstimator estimator) {
    this.estimator = estimator;
  }

  public CanonicalRequest compact(CanonicalRequest request, int contextWindow) {
    RequestBudget budget = RequestBudget.evaluate(request, estimator, contextWindow);
    if (budget.pressure() != RequestBudget.Pressure.HIGH
        && budget.pressure() != RequestBudget.Pressure.OVERFLOW) {
      return request;
    }
    int fixedMessageTokens =
        request.sections().stream()
            .filter(section -> section.kind() != PromptSectionKind.HISTORY)
            .mapToInt(section -> estimator.messages(section.messages()))
            .sum();
    int targetTokens = (int) Math.floor(contextWindow * TARGET_USAGE_RATIO);
    int historyTokenLimit =
        Math.max(
            256,
            targetTokens - budget.toolSchemaTokens() - budget.outputReserve() - fixedMessageTokens);
    List<PromptSection> compacted = new ArrayList<>();
    for (PromptSection section : request.sections()) {
      if (section.kind() != PromptSectionKind.HISTORY) {
        compacted.add(section);
        continue;
      }
      List<LlmMessage> kept =
          dropDanglingLeadingTools(keepNewest(section.messages(), historyTokenLimit));
      int removed = section.messages().size() - kept.size();
      if (removed > 0) {
        compacted.add(
            new PromptSection(
                "conversation-summary-auto",
                PromptSectionKind.CONVERSATION_SUMMARY,
                List.of(
                    LlmMessage.system(
                        "Earlier conversation compacted: "
                            + removed
                            + " messages omitted; durable facts and artifacts remain available.")),
                PromptStability.SESSION_STATIC,
                PromptCachePolicy.NONE,
                java.util.Map.of("compacted_messages", removed)));
      }
      compacted.add(
          new PromptSection(
              section.id(),
              section.kind(),
              kept,
              section.stability(),
              section.cachePolicy(),
              section.metadata()));
    }
    compacted.sort(java.util.Comparator.comparingInt(section -> section.kind().ordinal()));
    return new CanonicalRequest(
        request.model(),
        compacted,
        request.tools(),
        request.temperature(),
        request.maxTokens(),
        request.timeout(),
        request.metadata());
  }

  private List<LlmMessage> keepNewest(List<LlmMessage> messages, int tokenLimit) {
    List<LlmMessage> reversed = new ArrayList<>();
    int tokens = 0;
    for (int index = messages.size() - 1; index >= 0; index--) {
      LlmMessage message = messages.get(index);
      int candidate = estimator.messages(List.of(message));
      if (!reversed.isEmpty() && tokens + candidate > tokenLimit) {
        break;
      }
      reversed.add(message);
      tokens += candidate;
    }
    java.util.Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  /**
   * The newest-only suffix can start mid-turn (a tool message whose answering assistant was
   * dropped). Providers reject orphan tool messages, so drop them from the head.
   */
  private static List<LlmMessage> dropDanglingLeadingTools(List<LlmMessage> messages) {
    int first = 0;
    while (first < messages.size() && messages.get(first).role() == LlmRole.TOOL) {
      first++;
    }
    return first == 0 ? messages : messages.subList(first, messages.size());
  }
}
