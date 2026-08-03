package org.opengis.platform.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** UTF-8 JSON/JSONL persistence with atomic replacement for mutable snapshots. */
public class JsonFileStore {
  private static final Map<Path, Object> APPEND_LOCKS = new ConcurrentHashMap<>();

  private final ObjectMapper objectMapper;

  public JsonFileStore() {
    this(new ObjectMapper());
  }

  public JsonFileStore(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ObjectMapper objectMapper() {
    return objectMapper;
  }

  public JsonNode read(Path path) {
    if (!Files.exists(path)) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException | JacksonException exception) {
      throw new WorkspaceStoreException("Cannot read JSON: " + path, exception);
    }
  }

  public ObjectNode readObject(Path path) {
    JsonNode value = read(path);
    if (!value.isObject()) {
      throw new WorkspaceStoreException("Expected a JSON object: " + path);
    }
    return (ObjectNode) value;
  }

  public List<ObjectNode> readJsonLines(Path path) {
    List<ObjectNode> rows = new ArrayList<>();
    if (!Files.exists(path)) {
      return rows;
    }
    try {
      int lineNumber = 0;
      for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        lineNumber++;
        String line = rawLine.strip();
        if (line.isEmpty()) {
          continue;
        }
        JsonNode row = objectMapper.readTree(line);
        if (!row.isObject()) {
          throw new WorkspaceStoreException("Expected a JSON object at " + path + ":" + lineNumber);
        }
        rows.add((ObjectNode) row);
      }
      return rows;
    } catch (IOException | JacksonException exception) {
      throw new WorkspaceStoreException("Cannot read JSONL: " + path, exception);
    }
  }

  public void write(Path path, JsonNode value) {
    try {
      Path parent = requireParent(path);
      Files.createDirectories(parent);
      Path temporary = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
      try {
        String payload =
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        Files.writeString(
            temporary, payload, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        moveReplacing(temporary, path);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot write JSON: " + path, exception);
    }
  }

  public void append(Path path, JsonNode value) {
    Path normalized = path.toAbsolutePath().normalize();
    Object lock = APPEND_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
    synchronized (lock) {
      try {
        Files.createDirectories(requireParent(normalized));
        Files.writeString(
            normalized,
            objectMapper.writeValueAsString(value) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException exception) {
        throw new WorkspaceStoreException("Cannot append JSONL: " + path, exception);
      }
    }
  }

  public String readText(Path path) {
    if (!Files.exists(path)) {
      return "";
    }
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot read text: " + path, exception);
    }
  }

  public void writeText(Path path, String content) {
    try {
      Path parent = requireParent(path);
      Files.createDirectories(parent);
      Path temporary = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
      try {
        Files.writeString(
            temporary, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        moveReplacing(temporary, path);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new WorkspaceStoreException("Cannot write text: " + path, exception);
    }
  }

  private static Path requireParent(Path path) {
    Path parent = path.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new WorkspaceStoreException("Store path has no parent: " + path);
    }
    return parent;
  }

  private static void moveReplacing(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
