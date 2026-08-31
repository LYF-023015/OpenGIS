/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.registry;

import java.nio.file.Path;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.tool.api.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes one deterministic, human-readable snapshot of the effective tool registry. */
public final class ToolCatalogWriter {
  public static final String FILE_NAME = "tool-catalog.json";

  private final ToolRegistry registry;
  private final JsonFileStore files;

  public ToolCatalogWriter(ToolRegistry registry, ObjectMapper mapper) {
    this.registry = registry;
    files = new JsonFileStore(mapper);
  }

  public Path write(Path workspace) {
    if (workspace == null) {
      throw new IllegalArgumentException("Workspace is required for the tool catalog");
    }
    Path path = workspace.toAbsolutePath().normalize().resolve(FILE_NAME);
    files.write(path, snapshot());
    return path;
  }

  public ObjectNode snapshot() {
    ObjectMapper mapper = files.objectMapper();
    ObjectNode root = mapper.createObjectNode();
    root.put("schema_version", 1);
    root.put("total", registry.size());
    ArrayNode values = root.putArray("tools");
    registry.definitions().forEach(definition -> values.add(toJson(mapper, definition)));
    return root;
  }

  private static ObjectNode toJson(ObjectMapper mapper, ToolDefinition definition) {
    ObjectNode value = mapper.createObjectNode();
    value.put("name", definition.name());
    value.put("display_name", definition.displayName());
    value.put("description", definition.description());
    value.put("category", definition.category());
    value.put("group", definition.group());
    value.put("version", definition.version());
    value.put("risk", definition.risk().name());
    value.set("input_schema", definition.inputSchema());
    value.set("tags", mapper.valueToTree(definition.tags()));
    return value;
  }
}
