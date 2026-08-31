/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.operation;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** v2 manifest builders shared by built-in operations and workspace templates. */
public final class OperationManifests {
  private OperationManifests() {}

  public static ObjectNode builtin(
      ObjectMapper mapper,
      String id,
      String name,
      String description,
      String entryClass,
      Map<String, Object> inputProperties,
      List<String> required) {
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("schema_version", "2.0");
    manifest.put("api_version", "1.0");
    manifest.put("id", id);
    manifest.put("name", name);
    manifest.put("version", "1.0.0");
    manifest.put("revision", 1);
    manifest.put("status", "validated");
    manifest.put("description", description);
    manifest.put("scope", "builtin");
    manifest.put("read_only", true);
    ObjectNode runtime = manifest.putObject("runtime");
    runtime.put("language", "java");
    runtime.put("entry_class", entryClass);
    runtime.put("jdk", ">=21");
    runtime.putArray("dependencies");
    manifest.set(
        "input_schema",
        mapper.valueToTree(
            Map.of(
                "type",
                "object",
                "properties",
                inputProperties,
                "required",
                required,
                "additionalProperties",
                true)));
    manifest.set(
        "output_schema",
        mapper.valueToTree(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("success", Map.of("type", "boolean")),
                "required",
                List.of("success"))));
    manifest.putPOJO("permissions", List.of("workspace_files"));
    return manifest;
  }

  public static Map<String, Object> string(String description) {
    return Map.of("type", "string", "description", description);
  }

  public static Map<String, Object> number(String description, Number defaultValue) {
    return Map.of("type", "number", "description", description, "default", defaultValue);
  }
}
