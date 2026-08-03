package org.opengis.knowledge.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded, run-local memory used to stop repeated identical tool failures. */
public final class FailureMemory {
  private final int capacity;
  private final LinkedHashMap<String, FailureRecord> failures;

  public FailureMemory(int capacity) {
    this.capacity = Math.max(1, capacity);
    this.failures = new LinkedHashMap<>();
  }

  public synchronized FailureRecord record(String operation, String error) {
    String signature = signature(operation, error);
    FailureRecord previous = failures.get(signature);
    FailureRecord next =
        new FailureRecord(
            signature,
            operation,
            compact(error),
            previous == null ? 1 : previous.count() + 1,
            Instant.now());
    failures.put(signature, next);
    while (failures.size() > capacity) {
      failures.remove(failures.keySet().iterator().next());
    }
    return next;
  }

  public synchronized int count(String operation, String error) {
    FailureRecord value = failures.get(signature(operation, error));
    return value == null ? 0 : value.count();
  }

  public synchronized Map<String, FailureRecord> snapshot() {
    return Map.copyOf(failures);
  }

  private static String signature(String operation, String error) {
    return compact(operation).toLowerCase(Locale.ROOT)
        + ":"
        + compact(error).toLowerCase(Locale.ROOT).replaceAll("\\d+", "#");
  }

  private static String compact(String value) {
    String safe = value == null ? "" : value.replaceAll("\\s+", " ").strip();
    return safe.length() <= 500 ? safe : safe.substring(0, 500);
  }

  public record FailureRecord(
      String signature, String operation, String error, int count, Instant lastSeen) {}
}
