package org.opengis.platform.electron;

import java.nio.file.Path;
import org.opengis.platform.persistence.JsonFileStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Compatible Reader/Writer for Electron userData/settings.json and projects.json. */
public class ElectronDataStore {
  public static final int SCHEMA_VERSION = 2;

  private final Path userDataDirectory;
  private final JsonFileStore files;

  public ElectronDataStore(Path userDataDirectory) {
    this(userDataDirectory, new JsonFileStore());
  }

  public ElectronDataStore(Path userDataDirectory, JsonFileStore files) {
    this.userDataDirectory = userDataDirectory.toAbsolutePath().normalize();
    this.files = files;
  }

  public ObjectNode loadSettings() {
    ObjectNode merged = defaultSettings();
    JsonNode saved = files.read(userDataDirectory.resolve("settings.json"));
    if (saved.isObject()) {
      deepMerge(merged, (ObjectNode) saved);
    }
    merged.put("schemaVersion", SCHEMA_VERSION);
    ObjectNode backend = object(merged, "backend");
    backend.putIfAbsent(
        "preferredRuntime", files.objectMapper().getNodeFactory().stringNode("java"));
    backend.putIfAbsent(
        "fallbackRuntime", files.objectMapper().getNodeFactory().stringNode("python"));
    backend.put("protocolVersion", "3.0");
    return merged;
  }

  public void saveSettings(ObjectNode settings) {
    ObjectNode upgraded = defaultSettings();
    deepMerge(upgraded, settings);
    upgraded.put("schemaVersion", SCHEMA_VERSION);
    object(upgraded, "backend").put("protocolVersion", "3.0");
    files.write(userDataDirectory.resolve("settings.json"), upgraded);
  }

  public ObjectNode loadProjects() {
    JsonNode saved = files.read(userDataDirectory.resolve("projects.json"));
    ObjectNode projects =
        saved.isObject()
            ? ((ObjectNode) saved).deepCopy()
            : files.objectMapper().createObjectNode();
    if (!projects.path("projects").isArray()) {
      projects.set("projects", files.objectMapper().createArrayNode());
    }
    projects.put("schemaVersion", SCHEMA_VERSION);
    ObjectNode backend = object(projects, "backend");
    backend.put("storageOwner", "electron-main");
    backend.put("javaCompatible", true);
    return projects;
  }

  public void saveProjects(ObjectNode projects) {
    ObjectNode upgraded = projects.deepCopy();
    if (!upgraded.path("projects").isArray()) {
      upgraded.set("projects", files.objectMapper().createArrayNode());
    }
    upgraded.put("schemaVersion", SCHEMA_VERSION);
    ObjectNode backend = object(upgraded, "backend");
    backend.put("storageOwner", "electron-main");
    backend.put("javaCompatible", true);
    files.write(userDataDirectory.resolve("projects.json"), upgraded);
  }

  private ObjectNode defaultSettings() {
    ObjectNode root = files.objectMapper().createObjectNode();
    ObjectNode model = root.putObject("model");
    model.put("provider", "openai");
    model.put("modelName", "gpt-4o");
    model.put("temperature", 0.7);
    model.put("maxTokens", 4096);
    root.putObject("python").put("mode", "auto");
    ObjectNode appearance = root.putObject("appearance");
    appearance.put("theme", "dark");
    appearance.put("language", "en");
    appearance.put("fontSize", 14);
    appearance.put("mapStyle", "dark");
    ObjectNode agent = root.putObject("agent");
    agent.put("maxIterations", 10);
    agent.put("codeExecutionTimeout", 60);
    agent.put("requireConfirmation", true);
    agent.put("autoRenderResults", true);
    ObjectNode backend = root.putObject("backend");
    backend.put("preferredRuntime", "java");
    backend.put("fallbackRuntime", "python");
    backend.put("protocolVersion", "3.0");
    root.put("schemaVersion", SCHEMA_VERSION);
    return root;
  }

  private static void deepMerge(ObjectNode target, ObjectNode source) {
    source
        .properties()
        .forEach(
            entry -> {
              JsonNode current = target.get(entry.getKey());
              JsonNode incoming = entry.getValue();
              if (current != null && current.isObject() && incoming.isObject()) {
                deepMerge((ObjectNode) current, (ObjectNode) incoming);
              } else {
                target.set(entry.getKey(), incoming.deepCopy());
              }
            });
  }

  private static ObjectNode object(ObjectNode parent, String field) {
    if (parent.path(field).isObject()) {
      return (ObjectNode) parent.path(field);
    }
    ObjectNode value = parent.objectNode();
    parent.set(field, value);
    return value;
  }
}
