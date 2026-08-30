package org.opengis.knowledge.memory.consolidation;

import java.time.Duration;

/** Conservative defaults: archive low-value stale records instead of deleting them. */
public record MemoryConsolidationPolicy(
    Duration staleAfter, double minimumImportance, int maximumActivePerScope) {
  public MemoryConsolidationPolicy {
    staleAfter = staleAfter == null || staleAfter.isNegative() ? Duration.ofDays(180) : staleAfter;
    minimumImportance = Math.max(0.0, Math.min(1.0, minimumImportance));
    maximumActivePerScope = Math.max(1, maximumActivePerScope);
  }

  public static MemoryConsolidationPolicy defaults() {
    return new MemoryConsolidationPolicy(Duration.ofDays(180), 0.35, 1_000);
  }
}
