package org.opengis.tool.registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Dependency-light validator for the JSON Schema keywords emitted by OpenGIS tools. */
public final class JsonSchemaValidator {
  public void validate(JsonNode schema, JsonNode value) {
    List<String> violations = new ArrayList<>();
    validateAt("$", schema, value, violations);
    if (!violations.isEmpty()) {
      throw new SchemaValidationException(violations);
    }
  }

  private void validateAt(String path, JsonNode schema, JsonNode value, List<String> violations) {
    if (schema == null || schema.isMissingNode()) {
      return;
    }
    String type = schema.path("type").asString("");
    if (!type.isBlank() && !matches(type, value)) {
      violations.add(path + " must be " + type);
      return;
    }
    if (schema.path("enum").isArray()
        && schema.path("enum").valueStream().noneMatch(value::equals)) {
      violations.add(path + " must match one of the allowed values");
    }
    if (value != null && value.isNumber()) {
      if (schema.has("minimum") && value.asDouble() < schema.path("minimum").asDouble()) {
        violations.add(path + " is below minimum");
      }
      if (schema.has("maximum") && value.asDouble() > schema.path("maximum").asDouble()) {
        violations.add(path + " is above maximum");
      }
    }
    if (value != null && value.isString()) {
      if (schema.has("minLength") && value.asString().length() < schema.path("minLength").asInt()) {
        violations.add(path + " is shorter than minLength");
      }
      if (schema.has("maxLength") && value.asString().length() > schema.path("maxLength").asInt()) {
        violations.add(path + " is longer than maxLength");
      }
    }
    if (value != null && value.isArray()) {
      int size = value.size();
      if (schema.has("minItems") && size < schema.path("minItems").asInt()) {
        violations.add(path + " has too few items");
      }
      if (schema.has("maxItems") && size > schema.path("maxItems").asInt()) {
        violations.add(path + " has too many items");
      }
      for (int index = 0; index < size; index++) {
        validateAt(path + "[" + index + "]", schema.path("items"), value.get(index), violations);
      }
    }
    if (value != null && value.isObject()) {
      validateObject(path, schema, value, violations);
    }
  }

  private void validateObject(
      String path, JsonNode schema, JsonNode value, List<String> violations) {
    Set<String> required = new HashSet<>();
    schema.path("required").valueStream().forEach(node -> required.add(node.asString()));
    for (String field : required) {
      if (!value.has(field) || value.path(field).isNull()) {
        violations.add(path + "." + field + " is required");
      }
    }
    JsonNode properties = schema.path("properties");
    for (String name : value.propertyNames()) {
      if (properties.has(name)) {
        validateAt(path + "." + name, properties.path(name), value.path(name), violations);
      } else if (schema.has("additionalProperties")
          && !schema.path("additionalProperties").asBoolean(true)) {
        violations.add(path + "." + name + " is not allowed");
      }
    }
  }

  private static boolean matches(String type, JsonNode value) {
    if (value == null || value.isNull()) {
      return false;
    }
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isString();
      case "number" -> value.isNumber();
      case "integer" -> value.isIntegralNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> true;
    };
  }
}
