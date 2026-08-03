package org.opengis.ai.context;

import java.util.ArrayList;
import java.util.List;
import org.opengis.ai.model.LlmMessage;

/** Request-aware history compaction which never mutates the stable prefix or tool schemas. */
public final class RequestCompactor {
  private final TokenEstimator estimator;

  public RequestCompactor(TokenEstimator estimator) {
    this.estimator = estimator;
  }

  public CanonicalRequest compact(CanonicalRequest request, int contextWindow) {
    RequestBudget budget = RequestBudget.evaluate(request, estimator, contextWindow);
    if (budget.pressure() != RequestBudget.Pressure.OVERFLOW) {
      return request;
    }
    int availableForMessages = contextWindow - budget.toolSchemaTokens() - budget.outputReserve();
    List<PromptSection> compacted = new ArrayList<>();
    for (PromptSection section : request.sections()) {
      if (section.kind() != PromptSectionKind.HISTORY) {
        compacted.add(section);
        continue;
      }
      List<LlmMessage> kept =
          keepNewest(section.messages(), Math.max(256, availableForMessages / 2));
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
}
