package org.opengis.tool.builtin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class FileTools {
  private FileTools() {}

  static List<OpenGisTool> create(ObjectMapper mapper) {
    List<OpenGisTool> tools = new ArrayList<>();
    tools.add(
        tool(mapper, "read_file", "Read File", ToolRisk.READ, readSchema(mapper), FileTools::read));
    tools.add(
        tool(
            mapper,
            "list_directory",
            "List Directory",
            ToolRisk.READ,
            listSchema(mapper),
            FileTools::list));
    tools.add(
        tool(
            mapper,
            "file_exists",
            "Check File Exists",
            ToolRisk.READ,
            pathSchema(mapper),
            FileTools::exists));
    tools.add(
        tool(mapper, "glob", "Glob Files", ToolRisk.READ, globSchema(mapper), FileTools::glob));
    tools.add(
        tool(mapper, "grep", "Regex Search", ToolRisk.READ, grepSchema(mapper), FileTools::grep));
    tools.add(
        tool(
            mapper,
            "write_file",
            "Write File",
            ToolRisk.WRITE,
            writeSchema(mapper),
            FileTools::write));
    tools.add(
        tool(
            mapper, "edit_file", "Edit File", ToolRisk.WRITE, editSchema(mapper), FileTools::edit));
    tools.add(
        tool(
            mapper,
            "create_directory",
            "Create Directory",
            ToolRisk.WRITE,
            pathSchema(mapper),
            FileTools::mkdir));
    tools.add(
        tool(
            mapper,
            "copy_file",
            "Copy File or Directory",
            ToolRisk.WRITE,
            sourceTargetSchema(mapper),
            FileTools::copy));
    tools.add(
        tool(
            mapper,
            "move_file",
            "Move or Rename File",
            ToolRisk.DESTRUCTIVE,
            sourceTargetSchema(mapper),
            FileTools::move));
    tools.add(
        tool(
            mapper,
            "delete_file",
            "Delete File or Directory",
            ToolRisk.DESTRUCTIVE,
            deleteSchema(mapper),
            FileTools::delete));
    return List.copyOf(tools);
  }

  private static OpenGisTool tool(
      ObjectMapper mapper,
      String name,
      String displayName,
      ToolRisk risk,
      JsonNode schema,
      ToolExecutor executor) {
    return new FunctionalTool(
        new ToolDefinition(
            name,
            displayName,
            displayName + " within the current workspace.",
            "system",
            "core",
            "1.0.0",
            risk,
            schema,
            List.of("filesystem")),
        executor);
  }

  private static JsonNode read(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("file_path").asString());
    int offset = args.path("offset").asInt(1);
    int limit = args.path("limit").asInt(2000);
    if (!Files.isRegularFile(path)) {
      throw new ToolException("file_not_found", "Not a readable file: " + path);
    }
    try {
      List<String> selected = new ArrayList<>();
      int totalLines = 0;
      try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          totalLines++;
          if (totalLines >= offset && selected.size() < limit) {
            selected.add(line);
          }
          context.cancellation().throwIfCancelled();
        }
      }
      ObjectNode result = new ObjectMapper().createObjectNode();
      result.put("path", path.toString());
      result.put("output", String.join(System.lineSeparator(), selected));
      result.put("total_lines", totalLines);
      result.put("truncated", offset - 1 + selected.size() < totalLines);
      return result;
    } catch (IOException exception) {
      throw new ToolException("file_read_failed", "Cannot read file: " + path, exception);
    }
  }

  private static JsonNode list(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("path").asString());
    String pattern = args.path("pattern").asString("");
    if (!Files.isDirectory(path)) {
      throw new ToolException("not_a_directory", "Not a directory: " + path);
    }
    ObjectMapper mapper = new ObjectMapper();
    ArrayNode entries = mapper.createArrayNode();
    try (Stream<Path> stream = Files.list(path)) {
      stream
          .filter(
              item ->
                  pattern.isBlank()
                      || path.getFileSystem()
                          .getPathMatcher("glob:" + pattern)
                          .matches(item.getFileName()))
          .sorted(Comparator.comparing(item -> item.getFileName().toString()))
          .forEach(
              item -> {
                ObjectNode entry = entries.addObject();
                entry.put("name", item.getFileName().toString());
                entry.put("is_file", Files.isRegularFile(item));
                entry.put("is_dir", Files.isDirectory(item));
                try {
                  entry.put("size", Files.isRegularFile(item) ? Files.size(item) : 0);
                } catch (IOException ignored) {
                  entry.putNull("size");
                }
              });
    } catch (IOException exception) {
      throw new ToolException("directory_list_failed", "Cannot list directory: " + path, exception);
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("path", path.toString());
    result.put("count", entries.size());
    result.set("entries", entries);
    return result;
  }

  private static JsonNode exists(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("path").asString());
    ObjectNode result = new ObjectMapper().createObjectNode();
    result.put("path", path.toString());
    result.put("exists", Files.exists(path));
    result.put("is_file", Files.isRegularFile(path));
    result.put("is_dir", Files.isDirectory(path));
    return result;
  }

  private static JsonNode glob(JsonNode args, ToolExecutionContext context) {
    Path root = WorkspacePaths.resolve(context, args.path("path").asString("."));
    String pattern = args.path("pattern").asString();
    int limit = args.path("limit").asInt(1000);
    var matcher = root.getFileSystem().getPathMatcher("glob:" + pattern);
    ArrayNode matches = new ObjectMapper().createArrayNode();
    try (Stream<Path> stream = Files.walk(root)) {
      stream
          .filter(path -> !path.equals(root) && matcher.matches(root.relativize(path)))
          .limit(limit)
          .forEach(path -> matches.add(root.relativize(path).toString().replace('\\', '/')));
    } catch (IOException exception) {
      throw new ToolException("glob_failed", "Cannot scan workspace", exception);
    }
    ObjectNode result = new ObjectMapper().createObjectNode();
    result.set("matches", matches);
    result.put("count", matches.size());
    return result;
  }

  private static JsonNode grep(JsonNode args, ToolExecutionContext context) {
    Path root = WorkspacePaths.resolve(context, args.path("path").asString("."));
    Pattern pattern;
    try {
      pattern = Pattern.compile(args.path("pattern").asString());
    } catch (RuntimeException exception) {
      throw new ToolException("invalid_regex", "Invalid regular expression", exception);
    }
    int limit = args.path("limit").asInt(200);
    ArrayNode matches = new ObjectMapper().createArrayNode();
    try (Stream<Path> stream = Files.walk(root)) {
      for (Path path : stream.filter(Files::isRegularFile).toList()) {
        context.cancellation().throwIfCancelled();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
          String line;
          int lineNumber = 0;
          while ((line = reader.readLine()) != null && matches.size() < limit) {
            lineNumber++;
            if (pattern.matcher(line).find()) {
              ObjectNode match = matches.addObject();
              match.put("path", root.relativize(path).toString().replace('\\', '/'));
              match.put("line", lineNumber);
              match.put("text", line);
            }
            context.cancellation().throwIfCancelled();
          }
        } catch (IOException ignored) {
          continue;
        }
        if (matches.size() >= limit) {
          break;
        }
      }
    } catch (IOException exception) {
      throw new ToolException("grep_failed", "Cannot search workspace", exception);
    }
    ObjectNode result = new ObjectMapper().createObjectNode();
    result.set("matches", matches);
    result.put("count", matches.size());
    return result;
  }

  private static JsonNode write(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("file_path").asString());
    if (Files.exists(path) && !args.path("overwrite").asBoolean(false)) {
      throw new ToolException(
          "overwrite_requires_opt_in", "Existing file requires overwrite=true: " + path);
    }
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, args.path("content").asString(), StandardCharsets.UTF_8);
      context
          .uiRpc()
          .notify(
              "rpc.ui.fs.refresh_assets",
              new ObjectMapper()
                  .valueToTree(Map.of("path", path.toString(), "reason", "write_file")));
      return pathResult(path, "written");
    } catch (IOException exception) {
      throw new ToolException("file_write_failed", "Cannot write file: " + path, exception);
    } catch (IllegalStateException ignored) {
      return pathResult(path, "written");
    }
  }

  private static JsonNode edit(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("file_path").asString());
    try {
      String original = Files.readString(path, StandardCharsets.UTF_8);
      String oldValue = args.path("old_string").asString();
      if (!original.contains(oldValue)) {
        throw new ToolException("edit_target_not_found", "old_string was not found");
      }
      boolean replaceAll = args.path("replace_all").asBoolean(false);
      String updated =
          replaceAll
              ? original.replace(oldValue, args.path("new_string").asString())
              : original.replaceFirst(
                  Pattern.quote(oldValue),
                  java.util.regex.Matcher.quoteReplacement(args.path("new_string").asString()));
      Files.writeString(path, updated, StandardCharsets.UTF_8);
      ObjectNode result = (ObjectNode) pathResult(path, "edited");
      result.put("before_chars", original.length());
      result.put("after_chars", updated.length());
      return result;
    } catch (IOException exception) {
      throw new ToolException("file_edit_failed", "Cannot edit file: " + path, exception);
    }
  }

  private static JsonNode mkdir(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("path").asString());
    try {
      boolean existed = Files.exists(path);
      Files.createDirectories(path);
      ObjectNode result = (ObjectNode) pathResult(path, "directory");
      result.put("created", !existed);
      return result;
    } catch (IOException exception) {
      throw new ToolException("directory_create_failed", "Cannot create directory", exception);
    }
  }

  private static JsonNode copy(JsonNode args, ToolExecutionContext context) {
    Path source = WorkspacePaths.resolve(context, args.path("src").asString());
    Path target = WorkspacePaths.resolve(context, args.path("dst").asString());
    try {
      copyRecursively(source, target);
      return sourceTargetResult(source, target);
    } catch (IOException exception) {
      throw new ToolException("copy_failed", "Cannot copy path", exception);
    }
  }

  private static JsonNode move(JsonNode args, ToolExecutionContext context) {
    Path source = WorkspacePaths.resolve(context, args.path("src").asString());
    Path target = WorkspacePaths.resolve(context, args.path("dst").asString());
    try {
      Files.createDirectories(target.getParent());
      Files.move(source, target);
      return sourceTargetResult(source, target);
    } catch (IOException exception) {
      throw new ToolException("move_failed", "Cannot move path", exception);
    }
  }

  private static JsonNode delete(JsonNode args, ToolExecutionContext context) {
    Path path = WorkspacePaths.resolve(context, args.path("path").asString());
    try {
      if (Files.isDirectory(path) && args.path("recursive").asBoolean(false)) {
        Files.walkFileTree(
            path,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                  throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                  throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
              }
            });
      } else {
        Files.deleteIfExists(path);
      }
      return pathResult(path, "deleted");
    } catch (IOException exception) {
      throw new ToolException("delete_failed", "Cannot delete path", exception);
    }
  }

  private static void copyRecursively(Path source, Path target) throws IOException {
    if (Files.isDirectory(source)) {
      try (Stream<Path> stream = Files.walk(source)) {
        for (Path item : stream.toList()) {
          Path destination = target.resolve(source.relativize(item));
          if (Files.isDirectory(item)) {
            Files.createDirectories(destination);
          } else {
            Files.createDirectories(destination.getParent());
            Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    } else {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static JsonNode pathResult(Path path, String status) {
    ObjectNode result = new ObjectMapper().createObjectNode();
    result.put("success", true);
    result.put("status", status);
    result.put("path", path.toString());
    return result;
  }

  private static JsonNode sourceTargetResult(Path source, Path target) {
    ObjectNode result = new ObjectMapper().createObjectNode();
    result.put("success", true);
    result.put("src", source.toString());
    result.put("dst", target.toString());
    return result;
  }

  private static JsonNode pathSchema(ObjectMapper mapper) {
    return ToolSchemas.object(mapper, Map.of("path", ToolSchemas.string(mapper)), "path");
  }

  private static JsonNode readSchema(ObjectMapper mapper) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    fields.put("file_path", ToolSchemas.string(mapper));
    fields.put("offset", ToolSchemas.integer(mapper, 1, 10_000_000));
    fields.put("limit", ToolSchemas.integer(mapper, 1, 20_000));
    return ToolSchemas.object(mapper, fields, "file_path");
  }

  private static JsonNode listSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of("path", ToolSchemas.string(mapper), "pattern", ToolSchemas.optionalString(mapper)),
        "path");
  }

  private static JsonNode globSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of(
            "path", ToolSchemas.optionalString(mapper),
            "pattern", ToolSchemas.string(mapper),
            "limit", ToolSchemas.integer(mapper, 1, 20_000)),
        "pattern");
  }

  private static JsonNode grepSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of(
            "path", ToolSchemas.optionalString(mapper),
            "pattern", ToolSchemas.string(mapper),
            "limit", ToolSchemas.integer(mapper, 1, 5_000)),
        "pattern");
  }

  private static JsonNode writeSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of(
            "file_path", ToolSchemas.string(mapper),
            "content", ToolSchemas.optionalString(mapper),
            "overwrite", ToolSchemas.bool(mapper)),
        "file_path",
        "content");
  }

  private static JsonNode editSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of(
            "file_path", ToolSchemas.string(mapper),
            "old_string", ToolSchemas.string(mapper),
            "new_string", ToolSchemas.optionalString(mapper),
            "replace_all", ToolSchemas.bool(mapper)),
        "file_path",
        "old_string",
        "new_string");
  }

  private static JsonNode sourceTargetSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of("src", ToolSchemas.string(mapper), "dst", ToolSchemas.string(mapper)),
        "src",
        "dst");
  }

  private static JsonNode deleteSchema(ObjectMapper mapper) {
    return ToolSchemas.object(
        mapper,
        Map.of("path", ToolSchemas.string(mapper), "recursive", ToolSchemas.bool(mapper)),
        "path");
  }
}
