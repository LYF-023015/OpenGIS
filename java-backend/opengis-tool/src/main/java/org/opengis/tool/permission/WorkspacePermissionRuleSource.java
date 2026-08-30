package org.opengis.tool.permission;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads the Phase 3/Python-compatible .opengis/permissions.json store. */
public final class WorkspacePermissionRuleSource implements PermissionRuleSource {
  private final JsonFileStore files = new JsonFileStore();

  @Override
  public Optional<PermissionDecision> match(Path workspace, String toolName, String profileName) {
    return match(workspace, toolName, profileName, null);
  }

  @Override
  public Optional<PermissionDecision> match(
      Path workspace, String toolName, String profileName, JsonNode arguments) {
    ObjectNode root = files.readObject(new WorkspaceLayout(workspace).resolve("permissions.json"));
    if (!root.path("rules").isArray()) {
      return Optional.empty();
    }
    List<ObjectNode> rules =
        root.path("rules")
            .valueStream()
            .filter(JsonNode -> JsonNode.isObject())
            .map(JsonNode -> (ObjectNode) JsonNode)
            .toList();
    for (int index = rules.size() - 1; index >= 0; index--) {
      ObjectNode rule = rules.get(index);
      String pattern = rule.path("tool").asString(rule.path("pattern").asString(""));
      String ruleProfile = rule.path("profile_name").asString("");
      if (pattern.isBlank()
          || (!ruleProfile.isBlank() && !ruleProfile.equals(profileName))
          || !globMatches(pattern, toolName)
          || !argumentsMatch(rule.path("argument_patterns"), arguments)
          || !hostsMatch(rule.path("host_patterns"), arguments)) {
        continue;
      }
      try {
        PermissionAction action = PermissionAction.parse(rule.path("action").asString());
        return Optional.of(
            new PermissionDecision(
                action,
                rule.path("reason").asString("Matched persisted permission rule"),
                "persisted:" + rule.path("id").asString(pattern)));
      } catch (IllegalArgumentException ignored) {
        // Ignore old or corrupt actions and continue to lower-priority policy.
      }
    }
    return Optional.empty();
  }

  private static boolean argumentsMatch(JsonNode patterns, JsonNode arguments) {
    if (!patterns.isObject()) return true;
    if (arguments == null || !arguments.isObject()) return false;
    for (var entry : new ArrayList<>(patterns.properties())) {
      JsonNode actual = dotted(arguments, entry.getKey());
      if (!actual.isValueNode() || !entry.getValue().isTextual()) return false;
      if (!globMatches(normalize(entry.getValue().asString()), normalize(actual.asString())))
        return false;
    }
    return true;
  }

  private static boolean hostsMatch(JsonNode patterns, JsonNode arguments) {
    List<String> allowed = strings(patterns);
    if (allowed.isEmpty()) return true;
    List<String> hosts = new ArrayList<>();
    collectHosts(arguments, hosts);
    if (hosts.isEmpty()) return false;
    return hosts.stream()
        .allMatch(
            host ->
                allowed.stream()
                    .anyMatch(pattern -> globMatches(pattern.toLowerCase(Locale.ROOT), host)));
  }

  private static void collectHosts(JsonNode node, List<String> hosts) {
    if (node == null || !node.isObject()) return;
    for (var entry : new ArrayList<>(node.properties())) {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      JsonNode value = entry.getValue();
      if (value.isObject()) {
        collectHosts(value, hosts);
      } else if (value.isArray()) {
        value.forEach(child -> collectHosts(child, hosts));
      } else if (value.isTextual()
          && (key.equals("url")
              || key.equals("uri")
              || key.equals("endpoint")
              || key.equals("base_url")
              || key.endsWith("_url"))) {
        try {
          String host = URI.create(value.asString()).getHost();
          if (host != null && !host.isBlank()) hosts.add(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
          // Invalid URLs cannot satisfy a constrained allow rule.
        }
      }
    }
  }

  private static List<String> strings(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return List.of();
    if (value.isTextual()) return List.of(value.asString());
    if (!value.isArray()) return List.of();
    return value.valueStream().filter(JsonNode::isTextual).map(JsonNode::asString).toList();
  }

  private static JsonNode dotted(JsonNode root, String path) {
    JsonNode current = root;
    for (String part : path.split("\\.")) {
      current = current.path(part);
    }
    return current;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  private static boolean globMatches(String glob, String value) {
    StringBuilder regex = new StringBuilder("^");
    for (char character : glob.toCharArray()) {
      if (character == '*') {
        regex.append(".*");
      } else if (character == '?') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(character)));
      }
    }
    return Pattern.compile(regex.append('$').toString()).matcher(value).matches();
  }
}
