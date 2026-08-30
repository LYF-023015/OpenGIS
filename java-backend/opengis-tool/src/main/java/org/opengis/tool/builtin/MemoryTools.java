package org.opengis.tool.builtin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.opengis.knowledge.memory.MemoryDraft;
import org.opengis.knowledge.memory.MemoryKind;
import org.opengis.knowledge.memory.MemoryRepository;
import org.opengis.knowledge.memory.MemoryScope;
import org.opengis.knowledge.memory.MemoryStatus;
import org.opengis.knowledge.memory.MemoryUpdate;
import org.opengis.knowledge.memory.consolidation.MemoryConsolidationPolicy;
import org.opengis.knowledge.memory.consolidation.MemoryConsolidator;
import org.opengis.knowledge.memory.search.MemorySearchQuery;
import org.opengis.platform.persistence.JsonTypeReferences;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit memory inspection and governance tools; persistence remains in opengis-knowledge. */
final class MemoryTools {
  private MemoryTools() {}

  static List<OpenGisTool> create(ObjectMapper mapper) {
    return List.of(
        listMemories(mapper),
        remember(mapper),
        updateMemory(mapper),
        deleteMemory(mapper),
        consolidateMemory(mapper));
  }

  private static OpenGisTool listMemories(ObjectMapper mapper) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "query", ToolSchemas.optionalString(mapper),
                "scope", ToolSchemas.optionalString(mapper),
                "status", ToolSchemas.optionalString(mapper),
                "limit", ToolSchemas.integer(mapper, 1, 100)));
    return new FunctionalTool(
        new ToolDefinition(
            "list_memories",
            "List Memories",
            "Inspect or search scoped Agent memory with explainable hybrid relevance scores.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.READ,
            schema,
            List.of("memory", "search")),
        (arguments, context) -> {
          MemoryRepository repository = new MemoryRepository(context.workspace());
          String query = arguments.path("query").asString("").strip();
          int limit = arguments.path("limit").asInt(20);
          MemoryScope requestedScope =
              parseOptional(MemoryScope.class, arguments.path("scope").asString(""));
          MemoryStatus requestedStatus =
              parseOptional(MemoryStatus.class, arguments.path("status").asString(""));
          if (!query.isBlank()) {
            Set<MemoryScope> scopes =
                requestedScope == null ? Set.of(MemoryScope.values()) : Set.of(requestedScope);
            var results =
                repository.search(
                    new MemorySearchQuery(
                        query, limit, 20_000, scopes, context.conversationId(), context.runId()));
            return mapper.valueToTree(Map.of("memories", results));
          }
          var records =
              repository.list().stream()
                  .filter(record -> requestedScope == null || record.scope() == requestedScope)
                  .filter(record -> requestedStatus == null || record.status() == requestedStatus)
                  .limit(limit)
                  .toList();
          return mapper.valueToTree(Map.of("memories", records));
        });
  }

  private static OpenGisTool remember(ObjectMapper mapper) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "kind", ToolSchemas.optionalString(mapper),
                "content", ToolSchemas.string(mapper),
                "scope", ToolSchemas.optionalString(mapper),
                "confidence", ToolSchemas.number(mapper, 0.0, 1.0),
                "importance", ToolSchemas.number(mapper, 0.0, 1.0),
                "metadata", ToolSchemas.openObject(mapper)),
            "content");
    return new FunctionalTool(
        new ToolDefinition(
            "remember",
            "Remember",
            "Create or deduplicate a scoped durable memory with provenance and confidence.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.WRITE,
            schema,
            List.of("memory", "write")),
        (arguments, context) -> {
          MemoryKind kind =
              parse(MemoryKind.class, arguments.path("kind").asString("FACT"), "kind");
          MemoryScope scope =
              parse(MemoryScope.class, arguments.path("scope").asString("WORKSPACE"), "scope");
          MemoryDraft draft =
              new MemoryDraft(
                  kind,
                  arguments.path("content").asString(),
                  "tool:remember/run:" + context.runId(),
                  scope,
                  scopeId(scope, context.conversationId(), context.runId()),
                  arguments.path("confidence").asDouble(0.9),
                  arguments.path("importance").asDouble(0.7),
                  stringMap(mapper, arguments.path("metadata")));
          return mapper.valueToTree(new MemoryRepository(context.workspace()).add(draft));
        });
  }

  private static OpenGisTool updateMemory(ObjectMapper mapper) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "id", ToolSchemas.string(mapper),
                "content", ToolSchemas.optionalString(mapper),
                "confidence", ToolSchemas.number(mapper, 0.0, 1.0),
                "importance", ToolSchemas.number(mapper, 0.0, 1.0),
                "status", ToolSchemas.optionalString(mapper),
                "metadata", ToolSchemas.openObject(mapper)),
            "id");
    return new FunctionalTool(
        new ToolDefinition(
            "update_memory",
            "Update Memory",
            "Correct memory content, confidence, importance, status, or metadata by id.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.WRITE,
            schema,
            List.of("memory", "write")),
        (arguments, context) -> {
          MemoryUpdate update =
              new MemoryUpdate(
                  textOrNull(arguments, "content"),
                  doubleOrNull(arguments, "confidence"),
                  doubleOrNull(arguments, "importance"),
                  parseOptional(MemoryStatus.class, arguments.path("status").asString("")),
                  arguments.has("metadata") ? stringMap(mapper, arguments.path("metadata")) : null);
          return mapper.valueToTree(
              new MemoryRepository(context.workspace())
                  .update(arguments.path("id").asString(), update)
                  .orElseThrow(() -> new ToolException("memory_not_found", "Memory not found")));
        });
  }

  private static OpenGisTool deleteMemory(ObjectMapper mapper) {
    JsonNode schema = ToolSchemas.object(mapper, Map.of("id", ToolSchemas.string(mapper)), "id");
    return new FunctionalTool(
        new ToolDefinition(
            "delete_memory",
            "Delete Memory",
            "Permanently delete one memory by id. Prefer update_memory with ARCHIVED when possible.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.DESTRUCTIVE,
            schema,
            List.of("memory", "delete")),
        (arguments, context) -> {
          boolean deleted =
              new MemoryRepository(context.workspace()).delete(arguments.path("id").asString());
          if (!deleted) {
            throw new ToolException("memory_not_found", "Memory not found");
          }
          return mapper.valueToTree(Map.of("deleted", true));
        });
  }

  private static OpenGisTool consolidateMemory(ObjectMapper mapper) {
    return new FunctionalTool(
        new ToolDefinition(
            "consolidate_memory",
            "Consolidate Memory",
            "Archive duplicate, stale low-value, and over-capacity memory without deleting provenance.",
            "orchestration",
            "core",
            "1.0.0",
            ToolRisk.WRITE,
            ToolSchemas.object(mapper, Map.of()),
            List.of("memory", "maintenance")),
        (arguments, context) ->
            mapper.valueToTree(
                new MemoryConsolidator(new MemoryRepository(context.workspace()))
                    .consolidate(MemoryConsolidationPolicy.defaults())));
  }

  private static String scopeId(MemoryScope scope, String conversationId, String runId) {
    return switch (scope) {
      case GLOBAL, WORKSPACE -> "";
      case CONVERSATION -> conversationId == null ? "" : conversationId;
      case RUN -> runId == null ? "" : runId;
    };
  }

  private static Map<String, String> stringMap(ObjectMapper mapper, JsonNode node) {
    return node.isObject() ? mapper.convertValue(node, JsonTypeReferences.STRING_MAP) : Map.of();
  }

  private static String textOrNull(JsonNode arguments, String field) {
    return arguments.has(field) ? arguments.path(field).asString() : null;
  }

  private static Double doubleOrNull(JsonNode arguments, String field) {
    return arguments.has(field) ? arguments.path(field).asDouble() : null;
  }

  private static <T extends Enum<T>> T parse(Class<T> type, String value, String field) {
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ToolException("invalid_memory_" + field, "Invalid memory " + field + ": " + value);
    }
  }

  private static <T extends Enum<T>> T parseOptional(Class<T> type, String value) {
    return value == null || value.isBlank() ? null : parse(type, value, type.getSimpleName());
  }
}
