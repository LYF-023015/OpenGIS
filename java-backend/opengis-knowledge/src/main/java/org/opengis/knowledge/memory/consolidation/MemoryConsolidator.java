package org.opengis.knowledge.memory.consolidation;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.opengis.knowledge.memory.MemoryRecord;
import org.opengis.knowledge.memory.MemoryRepository;
import org.opengis.knowledge.memory.MemoryStatus;

/** Idempotent maintenance pass for duplicate, stale, and over-capacity memory. */
public final class MemoryConsolidator {
  private final MemoryRepository repository;

  public MemoryConsolidator(MemoryRepository repository) {
    this.repository = java.util.Objects.requireNonNull(repository, "repository");
  }

  public MemoryConsolidationReport consolidate(MemoryConsolidationPolicy policy) {
    List<MemoryRecord> active =
        repository.list().stream()
            .filter(record -> record.status() == MemoryStatus.ACTIVE)
            .toList();
    int duplicates = archiveDuplicates(active);
    active =
        repository.list().stream()
            .filter(record -> record.status() == MemoryStatus.ACTIVE)
            .toList();
    int stale = archiveStale(active, policy, Instant.now());
    active =
        repository.list().stream()
            .filter(record -> record.status() == MemoryStatus.ACTIVE)
            .toList();
    int overflow = archiveOverflow(active, policy.maximumActivePerScope());
    return new MemoryConsolidationReport(duplicates, stale, overflow);
  }

  private int archiveDuplicates(List<MemoryRecord> records) {
    Map<String, MemoryRecord> winners = new HashMap<>();
    Set<String> duplicates = new HashSet<>();
    Comparator<MemoryRecord> quality =
        Comparator.comparingDouble(MemoryRecord::confidence)
            .thenComparingDouble(MemoryRecord::importance)
            .thenComparing(MemoryRecord::updatedAt);
    for (MemoryRecord record : records) {
      String key =
          record.scope()
              + "|"
              + record.scopeId()
              + "|"
              + record.kind()
              + "|"
              + normalize(record.content());
      MemoryRecord winner = winners.get(key);
      if (winner == null) {
        winners.put(key, record);
      } else if (quality.compare(record, winner) > 0) {
        duplicates.add(winner.id());
        winners.put(key, record);
      } else {
        duplicates.add(record.id());
      }
    }
    duplicates.forEach(id -> repository.archive(id, "duplicate"));
    return duplicates.size();
  }

  private int archiveStale(
      List<MemoryRecord> records, MemoryConsolidationPolicy policy, Instant now) {
    List<String> stale =
        records.stream()
            .filter(record -> record.importance() < policy.minimumImportance())
            .filter(record -> record.accessCount() == 0)
            .filter(record -> record.updatedAt().plus(policy.staleAfter()).isBefore(now))
            .map(MemoryRecord::id)
            .toList();
    stale.forEach(id -> repository.archive(id, "stale-low-value"));
    return stale.size();
  }

  private int archiveOverflow(List<MemoryRecord> records, int maximum) {
    Map<String, List<MemoryRecord>> byScope =
        records.stream()
            .collect(
                Collectors.groupingBy(
                    record -> record.scope() + "|" + record.scopeId(), Collectors.toList()));
    int archived = 0;
    for (List<MemoryRecord> scopeRecords : byScope.values()) {
      if (scopeRecords.size() <= maximum) {
        continue;
      }
      List<MemoryRecord> lowest =
          scopeRecords.stream()
              .sorted(
                  Comparator.comparingDouble(MemoryRecord::importance)
                      .thenComparingInt(MemoryRecord::accessCount)
                      .thenComparing(MemoryRecord::updatedAt))
              .limit(scopeRecords.size() - maximum)
              .toList();
      lowest.forEach(record -> repository.archive(record.id(), "scope-capacity"));
      archived += lowest.size();
    }
    return archived;
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }
}
