package org.opengis.tool.builtin;

import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class ToolSchemas {
  private ToolSchemas() {}

  static ObjectNode object(
      ObjectMapper mapper, Map<String, JsonNode> properties, String... required) {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode fields = schema.putObject("properties");
    properties.forEach(fields::set);
    ArrayNode requiredFields = schema.putArray("required");
    for (String field : required) {
      requiredFields.add(field);
    }
    schema.put("additionalProperties", false);
    return schema;
  }

  static ObjectNode string(ObjectMapper mapper) {
    return mapper.createObjectNode().put("type", "string").put("minLength", 1);
  }

  static ObjectNode optionalString(ObjectMapper mapper) {
    return mapper.createObjectNode().put("type", "string");
  }

  static ObjectNode bool(ObjectMapper mapper) {
    return mapper.createObjectNode().put("type", "boolean");
  }

  static ObjectNode integer(ObjectMapper mapper, int minimum, int maximum) {
    return mapper
        .createObjectNode()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum);
  }

  static ObjectNode number(ObjectMapper mapper, double minimum, double maximum) {
    return mapper
        .createObjectNode()
        .put("type", "number")
        .put("minimum", minimum)
        .put("maximum", maximum);
  }

  static ObjectNode array(ObjectMapper mapper, JsonNode items, int minimum) {
    ObjectNode schema = mapper.createObjectNode().put("type", "array").put("minItems", minimum);
    schema.set("items", items);
    return schema;
  }

  static ObjectNode openObject(ObjectMapper mapper) {
    ObjectNode schema =
        mapper.createObjectNode().put("type", "object").put("additionalProperties", true);
    schema.putObject("properties");
    return schema;
  }
}
