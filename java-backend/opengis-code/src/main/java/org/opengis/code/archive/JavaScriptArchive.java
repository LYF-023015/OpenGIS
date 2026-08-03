package org.opengis.code.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ObjectNode;

/** Java-first script source, metadata, index and run-log archive. */
public final class JavaScriptArchive {
  private final Path root;
  private final JsonFileStore files;

  public JavaScriptArchive(Path workspace) {
    this.root = new WorkspaceLayout(workspace).resolve("scripts");
    this.files = new JsonFileStore();
  }

  public ArchivedScript archive(
      String semanticName,
      String entryClass,
      String source,
      String runId,
      List<String> dependencies,
      Map<String, Object> extra) {
    String id =
        Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14)
            + "-"
            + slug(semanticName);
    Path script = root.resolve(id + ".java");
    Path metadata = root.resolve(id + ".metadata.json");
    files.writeText(script, source.stripTrailing() + "\n");
    ObjectNode record = files.objectMapper().createObjectNode();
    record.put("schema_version", "2.0");
    record.put("language", "java");
    record.put("script_id", id);
    record.put("semantic_name", semanticName);
    record.put("entry_class", entryClass);
    record.put("run_id", runId == null ? "" : runId);
    record.put("script_path", root.relativize(script).toString().replace('\\', '/'));
    record.put("sha256", sha256(source));
    record.putPOJO("dependencies", dependencies == null ? List.of() : dependencies);
    record.putPOJO("metadata", extra == null ? Map.of() : extra);
    record.put("created_at", Instant.now().toString());
    files.write(metadata, record);
    files.append(root.resolve("_scripts_index.jsonl"), record);
    return new ArchivedScript(script, metadata, record);
  }

  public List<ObjectNode> list(String query, int limit) {
    if (!Files.isDirectory(root)) return List.of();
    String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
    List<ObjectNode> values = new ArrayList<>();
    try (var paths = Files.list(root)) {
      paths
          .filter(path -> path.getFileName().toString().endsWith(".metadata.json"))
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                ObjectNode value = files.readObject(path);
                if (needle.isBlank()
                    || value.toString().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                  values.add(value);
                }
              });
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot list script archive", exception);
    }
    return values.stream().limit(Math.max(1, Math.min(limit, 200))).toList();
  }

  public ObjectNode read(String scriptPath, int maxCharacters) {
    Path path = root.resolve(scriptPath).normalize();
    if (!path.startsWith(root)
        || !Files.isRegularFile(path)
        || !path.toString().endsWith(".java")) {
      throw new IllegalArgumentException("Archived Java script was not found");
    }
    String source = files.readText(path);
    int limit = Math.max(1_000, Math.min(maxCharacters, 1_000_000));
    ObjectNode value = files.objectMapper().createObjectNode();
    value.put("path", root.relativize(path).toString().replace('\\', '/'));
    value.put("language", "java");
    value.put("source", source.substring(0, Math.min(source.length(), limit)));
    value.put("truncated", source.length() > limit);
    Path metadata =
        path.resolveSibling(path.getFileName().toString().replace(".java", ".metadata.json"));
    if (Files.exists(metadata)) value.set("metadata", files.readObject(metadata));
    return value;
  }

  public Path root() {
    return root;
  }

  private static String slug(String value) {
    String result = value == null ? "script" : value.strip().replaceAll("[^\\p{L}\\p{N}._-]+", "-");
    result = result.replaceAll("^[._-]+|[._-]+$", "");
    return result.isBlank() ? "script" : result.substring(0, Math.min(result.length(), 64));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public record ArchivedScript(Path source, Path metadata, ObjectNode record) {}
}
