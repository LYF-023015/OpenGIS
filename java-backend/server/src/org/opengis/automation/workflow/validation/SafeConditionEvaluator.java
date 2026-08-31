/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.validation;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Bounded JSON-logic subset. It cannot invoke methods, access files, or evaluate source code. */
public final class SafeConditionEvaluator {
  private static final int MAX_DEPTH = 16;
  private static final int MAX_NODES = 256;
  private int visited;

  public boolean evaluate(JsonNode expression, Map<String, JsonNode> variables) {
    visited = 0;
    return truthy(value(expression, variables == null ? Map.of() : variables, 0));
  }

  private Object value(JsonNode node, Map<String, JsonNode> variables, int depth) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (depth > MAX_DEPTH || ++visited > MAX_NODES) {
      throw new IllegalArgumentException("Condition exceeds safety limits");
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.asDouble();
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isArray()) {
      return node.valueStream().map(item -> value(item, variables, depth + 1)).toList();
    }
    if (!node.isObject() || node.size() != 1) {
      throw new IllegalArgumentException("Condition operators must be single-key objects");
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
    Map.Entry<String, JsonNode> entry = fields.next();
    String operator = entry.getKey();
    JsonNode args = entry.getValue();
    if ("var".equals(operator)) {
      return resolveVariable(args.asString(), variables);
    }
    List<Object> values =
        args.isArray()
            ? args.valueStream().map(item -> value(item, variables, depth + 1)).toList()
            : Collections.singletonList(value(args, variables, depth + 1));
    return switch (operator) {
      case "==" ->
          require(values, 2) && Objects.equals(normalize(values.get(0)), normalize(values.get(1)));
      case "!=" ->
          require(values, 2) && !Objects.equals(normalize(values.get(0)), normalize(values.get(1)));
      case ">" -> compare(values) > 0;
      case ">=" -> compare(values) >= 0;
      case "<" -> compare(values) < 0;
      case "<=" -> compare(values) <= 0;
      case "and" -> values.stream().allMatch(SafeConditionEvaluator::truthy);
      case "or" -> values.stream().anyMatch(SafeConditionEvaluator::truthy);
      case "!" -> !truthy(values.isEmpty() ? null : values.getFirst());
      case "exists" -> !values.isEmpty() && values.getFirst() != null;
      case "in" -> values.size() == 2 && contains(values.get(1), values.get(0));
      default -> throw new IllegalArgumentException("Unsupported condition operator: " + operator);
    };
  }

  private static Object resolveVariable(String path, Map<String, JsonNode> variables) {
    String[] segments = path.split("\\.");
    JsonNode current = variables.get(segments[0]);
    for (int i = 1; current != null && i < segments.length; i++) {
      current = current.get(segments[i]);
    }
    if (current == null || current.isNull()) {
      return null;
    }
    if (current.isBoolean()) return current.asBoolean();
    if (current.isNumber()) return current.asDouble();
    if (current.isString()) return current.asString();
    return current;
  }

  private static int compare(List<Object> values) {
    if (!require(values, 2)
        || !(values.get(0) instanceof Number left)
        || !(values.get(1) instanceof Number right)) {
      throw new IllegalArgumentException("Comparison requires two numeric values");
    }
    return Double.compare(left.doubleValue(), right.doubleValue());
  }

  private static boolean require(List<Object> values, int size) {
    if (values.size() != size) {
      throw new IllegalArgumentException("Condition operator requires " + size + " arguments");
    }
    return true;
  }

  private static Object normalize(Object value) {
    return value instanceof Number number ? number.doubleValue() : value;
  }

  private static boolean contains(Object container, Object needle) {
    if (container instanceof List<?> values) return values.contains(needle);
    if (container instanceof String text && needle != null) return text.contains(needle.toString());
    return false;
  }

  private static boolean truthy(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean flag) return flag;
    if (value instanceof Number number) return number.doubleValue() != 0;
    if (value instanceof String text) return !text.isBlank();
    if (value instanceof List<?> list) return !list.isEmpty();
    return true;
  }
}
