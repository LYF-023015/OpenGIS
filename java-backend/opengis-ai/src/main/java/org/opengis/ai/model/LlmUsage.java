package org.opengis.ai.model;

/** Normalized provider usage, including prompt-cache accounting. */
public record LlmUsage(
    long promptTokens,
    long completionTokens,
    long cachedTokens,
    long cacheReadTokens,
    long cacheCreationTokens) {
  public static final LlmUsage EMPTY = new LlmUsage(0, 0, 0, 0, 0);

  public long totalTokens() {
    return promptTokens + completionTokens;
  }

  public LlmUsage plus(LlmUsage other) {
    if (other == null) {
      return this;
    }
    return new LlmUsage(
        promptTokens + other.promptTokens,
        completionTokens + other.completionTokens,
        cachedTokens + other.cachedTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheCreationTokens + other.cacheCreationTokens);
  }
}
