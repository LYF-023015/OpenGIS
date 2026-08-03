package org.opengis.tool.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ObjectNode;

/** Reads the Phase 3/Python-compatible .opengis/permissions.json store. */
public final class WorkspacePermissionRuleSource implements PermissionRuleSource {
  private final JsonFileStore files = new JsonFileStore();

  @Override
  public Optional<PermissionDecision> match(Path workspace, String toolName, String profileName) {
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
      String pattern = rule.path("tool").asText(rule.path("pattern").asText(""));
      String ruleProfile = rule.path("profile_name").asText("");
      if (pattern.isBlank()
          || (!ruleProfile.isBlank() && !ruleProfile.equals(profileName))
          || !globMatches(pattern, toolName)) {
        continue;
      }
      try {
        PermissionAction action = PermissionAction.parse(rule.path("action").asText());
        return Optional.of(
            new PermissionDecision(
                action,
                rule.path("reason").asText("Matched persisted permission rule"),
                "persisted:" + rule.path("id").asText(pattern)));
      } catch (IllegalArgumentException ignored) {
        // Ignore old or corrupt actions and continue to lower-priority policy.
      }
    }
    return Optional.empty();
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
