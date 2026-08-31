/** 文件职责：knowledge 后端领域：管理状态或持久化数据。 */
package org.opengis.assistant.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.opengis.assistant.memory.search.HashingMemoryEmbeddingProvider;
import org.opengis.assistant.memory.search.MemorySearchEngine;
import org.opengis.assistant.memory.search.MemorySearchQuery;
import org.opengis.assistant.memory.search.MemorySearchResult;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.JsonTypeReferences;
import org.opengis.core.persistence.WorkspaceLayout;
import org.opengis.core.security.SensitiveDataRedactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Scope-aware durable memory repository with backward-compatible workspace storage. */
public final class MemoryRepository {
  private static final String LEGACY_ID = "legacy-memory-md";
  private static final Object MEMORY_LOCK = new Object();

  private final JsonFileStore files = new JsonFileStore();
  private final Path workspacePath;
  private final Path globalPath;
  private final Path legacyPath;
  private final MemorySearchEngine search;

  public MemoryRepository(Path workspace) {
    this(workspace, new MemorySearchEngine(new HashingMemoryEmbeddingProvider()));
  }

  public MemoryRepository(Path workspace, MemorySearchEngine search) {
    this(
        workspace,
        Path.of(System.getProperty("user.home"), ".opengis", "memory", "records.json"),
        search);
  }

  public MemoryRepository(Path workspace, Path globalMemoryPath, MemorySearchEngine search) {
    WorkspaceLayout layout = new WorkspaceLayout(workspace);
    workspacePath = layout.resolve("memory/records.json");
    legacyPath = layout.resolve("memory.md");
    globalPath = globalMemoryPath.toAbsolutePath().normalize();
    this.search = java.util.Objects.requireNonNull(search, "search");
  }

  public List<MemoryRecord> list() {
    synchronized (lock()) {
      LinkedHashMap<String, MemoryRecord> records = new LinkedHashMap<>();
      read(globalPath).forEach(record -> records.put(record.id(), record));
      read(workspacePath).forEach(record -> records.put(record.id(), record));
      legacy().ifPresent(record -> records.putIfAbsent(record.id(), record));
      return List.copyOf(records.values());
    }
  }

  public Optional<MemoryRecord> find(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return list().stream().filter(record -> record.id().equals(id)).findFirst();
  }

  public MemoryRecord add(
      MemoryKind kind, String content, String source, Map<String, String> metadata) {
    return add(
        new MemoryDraft(kind, content, source, MemoryScope.WORKSPACE, "", 0.7, 0.5, metadata));
  }

  /** Deduplicates identical knowledge and supersedes conflicts sharing a memory_key. */
  public MemoryRecord add(MemoryDraft draft) {
    draft = redact(draft);
    synchronized (lock()) {
      List<MemoryRecord> records = mutableRecords();
      String normalized = normalize(draft.content());
      for (int index = 0; index < records.size(); index++) {
        MemoryRecord current = records.get(index);
        if (current.status() == MemoryStatus.ACTIVE
            && current.kind() == draft.kind()
            && current.scope() == draft.scope()
            && current.scopeId().equals(draft.scopeId())
            && normalize(current.content()).equals(normalized)) {
          Map<String, String> metadata = new LinkedHashMap<>(current.metadata());
          metadata.putAll(draft.metadata());
          MemoryRecord updated =
              current.update(
                  new MemoryUpdate(
                      null,
                      Math.max(current.confidence(), draft.confidence()),
                      Math.max(current.importance(), draft.importance()),
                      MemoryStatus.ACTIVE,
                      metadata),
                  Instant.now());
          records.set(index, updated);
          write(records);
          return updated;
        }
      }
      supersedeConflicts(records, draft);
      Instant now = Instant.now();
      MemoryRecord record =
          new MemoryRecord(
              UUID.randomUUID().toString().replace("-", ""),
              draft.kind(),
              draft.content(),
              draft.source(),
              now,
              now,
              Instant.EPOCH,
              draft.scope(),
              draft.scopeId(),
              draft.confidence(),
              draft.importance(),
              0,
              MemoryStatus.ACTIVE,
              draft.metadata());
      records.add(record);
      write(records);
      return record;
    }
  }

  public Optional<MemoryRecord> update(String id, MemoryUpdate update) {
    update = redact(update);
    synchronized (lock()) {
      List<MemoryRecord> records = mutableRecords();
      for (int index = 0; index < records.size(); index++) {
        MemoryRecord current = records.get(index);
        if (current.id().equals(id)) {
          MemoryRecord updated = current.update(update, Instant.now());
          records.set(index, updated);
          write(records);
          return Optional.of(updated);
        }
      }
      return Optional.empty();
    }
  }

  public boolean delete(String id) {
    synchronized (lock()) {
      List<MemoryRecord> records = mutableRecords();
      boolean removed = records.removeIf(record -> record.id().equals(id));
      if (removed) {
        write(records);
      }
      return removed;
    }
  }

  public Optional<MemoryRecord> archive(String id, String reason) {
    return update(
        id,
        new MemoryUpdate(
            null,
            null,
            null,
            MemoryStatus.ARCHIVED,
            Map.of("archive_reason", reason == null ? "manual" : reason)));
  }

  public Optional<MemoryRecord> supersede(String id, String replacementId) {
    return update(
        id,
        new MemoryUpdate(
            null,
            null,
            null,
            MemoryStatus.SUPERSEDED,
            Map.of("superseded_by", replacementId == null ? "" : replacementId)));
  }

  public List<MemorySearchResult> search(MemorySearchQuery query) {
    List<MemorySearchResult> results = search.search(list(), query, Instant.now());
    recordAccess(results.stream().map(result -> result.record().id()).toList());
    return results;
  }

  /** Compatibility method used by the original ContextManager. */
  public List<MemoryRecord> relevant(String task, int limit) {
    return search(MemorySearchQuery.workspace(task, limit, 6_000)).stream()
        .map(MemorySearchResult::record)
        .toList();
  }

  private void recordAccess(List<String> ids) {
    if (ids.isEmpty()) {
      return;
    }
    synchronized (lock()) {
      List<MemoryRecord> records = mutableRecords();
      boolean changed = false;
      Instant now = Instant.now();
      for (int index = 0; index < records.size(); index++) {
        if (ids.contains(records.get(index).id())) {
          records.set(index, records.get(index).accessed(now));
          changed = true;
        }
      }
      if (changed) {
        write(records);
      }
    }
  }

  private List<MemoryRecord> mutableRecords() {
    return new ArrayList<>(
        list().stream().filter(record -> !record.id().equals(LEGACY_ID)).toList());
  }

  private void supersedeConflicts(List<MemoryRecord> records, MemoryDraft draft) {
    String key = draft.metadata().getOrDefault("memory_key", "").strip();
    if (key.isBlank()) {
      return;
    }
    Instant now = Instant.now();
    for (int index = 0; index < records.size(); index++) {
      MemoryRecord current = records.get(index);
      if (current.status() == MemoryStatus.ACTIVE
          && current.scope() == draft.scope()
          && current.scopeId().equals(draft.scopeId())
          && key.equals(current.metadata().get("memory_key"))) {
        records.set(
            index,
            current.update(
                new MemoryUpdate(
                    null, null, null, MemoryStatus.SUPERSEDED, Map.of("conflict_key", key)),
                now));
      }
    }
  }

  private List<MemoryRecord> read(Path path) {
    if (!Files.exists(path)) {
      return List.of();
    }
    JsonNode rows = files.readObject(path).path("records");
    if (!rows.isArray()) {
      return List.of();
    }
    List<MemoryRecord> records = new ArrayList<>();
    rows.forEach(node -> records.add(fromJson(node)));
    return records;
  }

  private Optional<MemoryRecord> legacy() {
    if (!Files.isRegularFile(legacyPath)) {
      return Optional.empty();
    }
    String legacy = SensitiveDataRedactor.redactText(files.readText(legacyPath));
    if (legacy.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new MemoryRecord(
            LEGACY_ID,
            MemoryKind.FACT,
            legacy,
            legacyPath.toString(),
            Instant.EPOCH,
            Instant.EPOCH,
            Instant.EPOCH,
            MemoryScope.WORKSPACE,
            "",
            0.5,
            0.4,
            0,
            MemoryStatus.ACTIVE,
            Map.of("format", "legacy-markdown")));
  }

  private void write(List<MemoryRecord> records) {
    write(
        globalPath,
        records.stream().filter(record -> record.scope() == MemoryScope.GLOBAL).toList());
    write(
        workspacePath,
        records.stream().filter(record -> record.scope() != MemoryScope.GLOBAL).toList());
  }

  private void write(Path path, List<MemoryRecord> records) {
    ObjectNode root = files.objectMapper().createObjectNode();
    root.put("schema_version", 2);
    ArrayNode array = root.putArray("records");
    records.forEach(
        record ->
            array.add(SensitiveDataRedactor.redact(files.objectMapper().valueToTree(record))));
    files.write(path, root);
  }

  private static MemoryDraft redact(MemoryDraft draft) {
    return new MemoryDraft(
        draft.kind(),
        SensitiveDataRedactor.redactText(draft.content()),
        SensitiveDataRedactor.redactText(draft.source()),
        draft.scope(),
        draft.scopeId(),
        draft.confidence(),
        draft.importance(),
        redact(draft.metadata()));
  }

  private static MemoryUpdate redact(MemoryUpdate update) {
    if (update == null) throw new IllegalArgumentException("Memory update is required");
    return new MemoryUpdate(
        SensitiveDataRedactor.redactText(update.content()),
        update.confidence(),
        update.importance(),
        update.status(),
        update.metadata() == null ? null : redact(update.metadata()));
  }

  private static Map<String, String> redact(Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) return Map.of();
    Map<String, String> result = new LinkedHashMap<>();
    metadata.forEach(
        (key, value) ->
            result.put(
                key,
                SensitiveDataRedactor.isSensitiveKey(key)
                    ? SensitiveDataRedactor.REDACTED
                    : SensitiveDataRedactor.redactText(value)));
    return Map.copyOf(result);
  }

  private MemoryRecord fromJson(JsonNode node) {
    Instant created = instant(node, "createdAt", "created_at", Instant.EPOCH);
    return new MemoryRecord(
        node.path("id").asString(),
        enumeration(MemoryKind.class, node.path("kind").asString(), MemoryKind.FACT),
        node.path("content").asString(),
        node.path("source").asString("unknown"),
        created,
        instant(node, "updatedAt", "updated_at", created),
        instant(node, "lastUsedAt", "last_used_at", Instant.EPOCH),
        scope(node.path("scope").asString()),
        node.path("scopeId").asString(node.path("scope_id").asString()),
        node.path("confidence").asDouble(0.7),
        node.path("importance").asDouble(0.5),
        node.path("accessCount").asInt(node.path("access_count").asInt(0)),
        enumeration(MemoryStatus.class, node.path("status").asString(), MemoryStatus.ACTIVE),
        node.path("metadata").isObject()
            ? files
                .objectMapper()
                .convertValue(node.path("metadata"), JsonTypeReferences.STRING_MAP)
            : Map.of());
  }

  private static MemoryScope scope(String value) {
    if ("project".equalsIgnoreCase(value)) {
      return MemoryScope.WORKSPACE;
    }
    return enumeration(MemoryScope.class, value, MemoryScope.WORKSPACE);
  }

  private static Instant instant(
      JsonNode node, String camelCase, String snakeCase, Instant fallback) {
    JsonNode snakeValue = node.path(snakeCase);
    if (snakeValue.isNumber()) {
      return Instant.ofEpochMilli((long) (snakeValue.asDouble() * 1000));
    }
    String value = node.path(camelCase).asString(node.path(snakeCase).asString());
    if (value.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      return fallback;
    }
  }

  private static <T extends Enum<T>> T enumeration(Class<T> type, String value, T fallback) {
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return fallback;
    }
  }

  private static String normalize(String content) {
    return content.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }

  private Object lock() {
    return MEMORY_LOCK;
  }
}
