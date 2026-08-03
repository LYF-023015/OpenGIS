package org.opengis.knowledge.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Structured memory store which also reads the legacy .opengis/memory.md file. */
public final class MemoryRepository {
  private final JsonFileStore files = new JsonFileStore();
  private final Path structuredPath;
  private final Path legacyPath;

  public MemoryRepository(Path workspace) {
    WorkspaceLayout layout = new WorkspaceLayout(workspace);
    structuredPath = layout.resolve("memory/records.json");
    legacyPath = layout.resolve("memory.md");
  }

  public synchronized List<MemoryRecord> list() {
    List<MemoryRecord> records = new ArrayList<>();
    if (Files.exists(structuredPath)) {
      JsonNode rows = files.readObject(structuredPath).path("records");
      if (rows.isArray()) {
        rows.forEach(node -> records.add(fromJson(node)));
      }
    }
    if (Files.isRegularFile(legacyPath)) {
      String legacy = files.readText(legacyPath);
      if (!legacy.isBlank()) {
        records.add(
            new MemoryRecord(
                "legacy-memory-md",
                MemoryKind.FACT,
                legacy,
                legacyPath.toString(),
                Instant.EPOCH,
                Map.of("format", "legacy-markdown")));
      }
    }
    return List.copyOf(records);
  }

  public synchronized MemoryRecord add(
      MemoryKind kind, String content, String source, Map<String, String> metadata) {
    MemoryRecord record =
        new MemoryRecord(
            UUID.randomUUID().toString().replace("-", ""),
            kind,
            content,
            source,
            Instant.now(),
            metadata);
    List<MemoryRecord> records =
        new ArrayList<>(
            list().stream().filter(item -> !item.id().equals("legacy-memory-md")).toList());
    records.add(record);
    write(records);
    return record;
  }

  /** Lightweight task relevance projection; deterministic and safe for offline use. */
  public List<MemoryRecord> relevant(String task, int limit) {
    String[] terms = (task == null ? "" : task.toLowerCase(Locale.ROOT)).split("\\s+");
    return list().stream()
        .sorted(
            java.util.Comparator.comparingInt((MemoryRecord record) -> score(record, terms))
                .reversed()
                .thenComparing(MemoryRecord::createdAt, java.util.Comparator.reverseOrder()))
        .limit(Math.max(0, limit))
        .toList();
  }

  private void write(List<MemoryRecord> records) {
    ObjectNode root = files.objectMapper().createObjectNode();
    root.put("schema_version", 1);
    ArrayNode array = root.putArray("records");
    records.forEach(record -> array.add(files.objectMapper().valueToTree(record)));
    files.write(structuredPath, root);
  }

  private MemoryRecord fromJson(JsonNode node) {
    MemoryKind kind;
    try {
      kind = MemoryKind.valueOf(node.path("kind").asText("FACT").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      kind = MemoryKind.FACT;
    }
    Map<String, String> metadata =
        node.path("metadata").isObject()
            ? files.objectMapper().convertValue(node.path("metadata"), Map.class)
            : Map.of();
    return new MemoryRecord(
        node.path("id").asText(),
        kind,
        node.path("content").asText(),
        node.path("source").asText("unknown"),
        Instant.parse(
            node.path("createdAt")
                .asText(node.path("created_at").asText(Instant.EPOCH.toString()))),
        metadata);
  }

  private static int score(MemoryRecord record, String[] terms) {
    String haystack = (record.content() + " " + record.metadata()).toLowerCase(Locale.ROOT);
    int score = 0;
    for (String term : terms) {
      if (term.length() >= 2 && haystack.contains(term)) {
        score++;
      }
    }
    return score;
  }
}
