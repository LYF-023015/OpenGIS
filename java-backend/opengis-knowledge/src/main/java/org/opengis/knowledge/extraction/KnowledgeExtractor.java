package org.opengis.knowledge.extraction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opengis.knowledge.memory.MemoryKind;
import org.opengis.knowledge.memory.MemoryRecord;
import org.opengis.knowledge.memory.MemoryRepository;

/** Post-run deterministic extractor; failures here never change the run's terminal state. */
public final class KnowledgeExtractor {
  public List<MemoryRecord> extract(Path workspace, String runId, String finalAnswer) {
    if (finalAnswer == null || finalAnswer.isBlank()) {
      return List.of();
    }
    MemoryRepository repository = new MemoryRepository(workspace);
    List<MemoryRecord> extracted = new ArrayList<>();
    for (String line : finalAnswer.lines().toList()) {
      Parsed parsed = parse(line.strip());
      if (parsed != null) {
        extracted.add(
            repository.add(
                parsed.kind(), parsed.content(), "run:" + runId, Map.of("extractor", "marker-v1")));
      }
    }
    return List.copyOf(extracted);
  }

  private static Parsed parse(String line) {
    for (MemoryKind kind : List.of(MemoryKind.FACT, MemoryKind.RECIPE, MemoryKind.DATASET_CARD)) {
      String marker = kind.name() + ":";
      if (line.regionMatches(true, 0, marker, 0, marker.length())) {
        String content = line.substring(marker.length()).strip();
        return content.isBlank() ? null : new Parsed(kind, content);
      }
    }
    return null;
  }

  private record Parsed(MemoryKind kind, String content) {}
}
