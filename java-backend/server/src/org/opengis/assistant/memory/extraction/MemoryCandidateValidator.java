/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory.extraction;

import java.util.Optional;
import java.util.regex.Pattern;

/** Rejects weak, oversized, or secret-looking candidates before persistence. */
public final class MemoryCandidateValidator {
  private static final int MAX_CONTENT_CHARS = 2_000;
  private static final double MINIMUM_CONFIDENCE = 0.72;
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)(api[_ -]?key|access[_ -]?token|secret|password|passwd|bearer\\s+[a-z0-9._-]+)\\s*[:=]");

  public Optional<MemoryCandidate> validate(MemoryCandidate candidate) {
    if (candidate == null || candidate.kind() == null || candidate.scope() == null) {
      return Optional.empty();
    }
    String content = candidate.content() == null ? "" : candidate.content().strip();
    if (content.length() < 8
        || content.length() > MAX_CONTENT_CHARS
        || candidate.confidence() < MINIMUM_CONFIDENCE
        || SECRET.matcher(content).find()) {
      return Optional.empty();
    }
    return Optional.of(
        new MemoryCandidate(
            candidate.kind(),
            content,
            candidate.scope(),
            candidate.scopeId(),
            candidate.confidence(),
            candidate.importance(),
            candidate.reason(),
            candidate.metadata()));
  }
}
