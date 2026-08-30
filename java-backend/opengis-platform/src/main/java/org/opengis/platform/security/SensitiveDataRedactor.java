package org.opengis.platform.security;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared fail-safe redaction for durable logs, run archives, and memory records. */
public final class SensitiveDataRedactor {
  public static final String REDACTED = "[REDACTED]";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> SENSITIVE_KEYS =
      Set.of(
          "password",
          "passwd",
          "secret",
          "clientsecret",
          "apikey",
          "accesstoken",
          "refreshtoken",
          "authtoken",
          "authorization",
          "credential",
          "credentials",
          "cookie",
          "setcookie",
          "privatekey");

  private static final Pattern BEARER =
      Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|password|passwd|client[_-]?secret)\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+");
  private static final Pattern QUERY_PARAMETER =
      Pattern.compile(
          "(?i)([?&](?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token)=)[^&#\\s]+");

  private SensitiveDataRedactor() {}

  public static JsonNode redact(JsonNode value) {
    if (value == null || value.isNull()) return value;
    JsonNode copy = value.deepCopy();
    redactNode(copy);
    return copy;
  }

  public static String redactText(String value) {
    if (value == null || value.isEmpty()) return value;
    String redacted = BEARER.matcher(value).replaceAll("$1" + REDACTED);
    redacted = ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
    return QUERY_PARAMETER.matcher(redacted).replaceAll("$1" + REDACTED);
  }

  public static boolean isSensitiveKey(String key) {
    if (key == null) return false;
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return SENSITIVE_KEYS.contains(normalized);
  }

  private static void redactNode(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (var entry : new ArrayList<>(object.properties())) {
        if (isSensitiveKey(entry.getKey())) {
          object.put(entry.getKey(), REDACTED);
        } else if (entry.getValue().isTextual()) {
          object.put(entry.getKey(), redactText(entry.getValue().asString()));
        } else {
          redactNode(entry.getValue());
        }
      }
    } else if (node instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        JsonNode child = array.get(index);
        if (child.isTextual())
          array.set(index, MAPPER.getNodeFactory().stringNode(redactText(child.asString())));
        else redactNode(child);
      }
    }
  }
}
