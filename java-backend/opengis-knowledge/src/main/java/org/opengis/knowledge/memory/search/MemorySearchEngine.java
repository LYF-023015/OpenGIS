package org.opengis.knowledge.memory.search;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.opengis.knowledge.memory.MemoryRecord;
import org.opengis.knowledge.memory.MemoryStatus;

/**
 * Hybrid lexical/vector retrieval with relevance filtering and a strict prompt character budget.
 */
public final class MemorySearchEngine {
  private static final double MINIMUM_SCORE = 0.12;
  private final MemoryEmbeddingProvider embeddings;

  public MemorySearchEngine(MemoryEmbeddingProvider embeddings) {
    this.embeddings = java.util.Objects.requireNonNull(embeddings, "embeddings");
  }

  public List<MemorySearchResult> search(
      List<MemoryRecord> records, MemorySearchQuery query, Instant now) {
    if (query.text().isBlank() || query.limit() == 0 || query.maxChars() == 0) {
      return List.of();
    }
    Set<String> terms = terms(query.text());
    double[] queryVector = embeddings.embed(query.text());
    List<MemorySearchResult> ranked =
        records.stream()
            .filter(record -> visible(record, query))
            .map(record -> score(record, terms, queryVector, now))
            .filter(
                result ->
                    result.score() >= MINIMUM_SCORE
                        && (result.lexicalScore() > 0.0 || result.vectorScore() >= 0.20))
            .sorted(
                Comparator.comparingDouble(MemorySearchResult::score)
                    .reversed()
                    .thenComparing(
                        result -> result.record().updatedAt(), Comparator.reverseOrder()))
            .toList();
    List<MemorySearchResult> selected = new ArrayList<>();
    int characters = 0;
    for (MemorySearchResult result : ranked) {
      int next = result.record().content().length();
      if (characters + next > query.maxChars()) {
        continue;
      }
      selected.add(result);
      characters += next;
      if (selected.size() >= query.limit()) {
        break;
      }
    }
    return List.copyOf(selected);
  }

  private MemorySearchResult score(
      MemoryRecord record, Set<String> queryTerms, double[] queryVector, Instant now) {
    Set<String> recordTerms = terms(record.content() + " " + record.metadata());
    long overlap = queryTerms.stream().filter(recordTerms::contains).count();
    double lexical = queryTerms.isEmpty() ? 0.0 : (double) overlap / queryTerms.size();
    double vector = Math.max(0.0, cosine(queryVector, embeddings.embed(record.content())));
    long ageDays = Math.max(0, Duration.between(record.updatedAt(), now).toDays());
    double recency = 1.0 / (1.0 + ageDays / 30.0);
    double score =
        lexical * 0.45
            + vector * 0.25
            + recency * 0.10
            + record.importance() * 0.10
            + record.confidence() * 0.10;
    return new MemorySearchResult(record, score, lexical, vector, recency);
  }

  private static boolean visible(MemoryRecord record, MemorySearchQuery query) {
    if (record.status() != MemoryStatus.ACTIVE || !query.scopes().contains(record.scope())) {
      return false;
    }
    return switch (record.scope()) {
      case GLOBAL, WORKSPACE -> true;
      case CONVERSATION -> record.scopeId().equals(query.conversationId());
      case RUN -> record.scopeId().equals(query.runId());
    };
  }

  private static Set<String> terms(String text) {
    Set<String> values = new HashSet<>();
    String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
    for (String value : normalized.split("[^\\p{L}\\p{N}_.:-]+")) {
      if (value.length() >= 2) {
        values.add(value);
      }
    }
    return values;
  }

  private static double cosine(double[] left, double[] right) {
    int length = Math.min(left.length, right.length);
    double value = 0.0;
    for (int index = 0; index < length; index++) {
      value += left[index] * right[index];
    }
    return value;
  }
}
