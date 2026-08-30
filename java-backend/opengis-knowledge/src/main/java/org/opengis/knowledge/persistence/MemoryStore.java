package org.opengis.knowledge.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import org.opengis.platform.security.SensitiveDataRedactor;
import tools.jackson.databind.node.ObjectNode;

/** Python-compatible structured and legacy workspace memory store. */
public class MemoryStore {
  private static final List<String> FILES =
      List.of("facts.jsonl", "recipes.jsonl", "datasets.jsonl", "failures.jsonl");

  private final JsonFileStore files = new JsonFileStore();
  private final WorkspaceLayout layout;

  public MemoryStore(Path workspaceRoot) {
    layout = new WorkspaceLayout(workspaceRoot);
  }

  public void append(ObjectNode record) {
    files.append(
        layout.resolve("memory/" + filename(record.path("kind").asString())),
        SensitiveDataRedactor.redact(record));
  }

  public List<ObjectNode> list() {
    List<ObjectNode> records = new ArrayList<>();
    FILES.forEach(name -> records.addAll(files.readJsonLines(layout.resolve("memory/" + name))));
    records.sort(
        Comparator.comparingDouble(
                (ObjectNode record) ->
                    Math.max(
                        record.path("last_used_at").asDouble(),
                        record.path("created_at").asDouble()))
            .reversed());
    return records;
  }

  public String readLegacyMarkdown() {
    return files.readText(layout.resolve("memory.md"));
  }

  public void writeLegacyMarkdown(String markdown) {
    files.writeText(layout.resolve("memory.md"), SensitiveDataRedactor.redactText(markdown));
  }

  private static String filename(String kind) {
    String normalized = kind == null ? "fact" : kind.toLowerCase(Locale.ROOT);
    if (List.of("recipe", "procedure").contains(normalized)) {
      return "recipes.jsonl";
    }
    if (List.of("dataset", "dataset_card", "layer", "artifact").contains(normalized)) {
      return "datasets.jsonl";
    }
    if (List.of("failure", "failure_lesson", "bug_pattern", "error_lesson").contains(normalized)) {
      return "failures.jsonl";
    }
    return "facts.jsonl";
  }
}
