package org.opengis.tool.permission;

import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

@FunctionalInterface
public interface PermissionRuleSource {
  Optional<PermissionDecision> match(Path workspace, String toolName, String profileName);

  default Optional<PermissionDecision> match(
      Path workspace, String toolName, String profileName, JsonNode arguments) {
    return match(workspace, toolName, profileName);
  }

  static PermissionRuleSource empty() {
    return (workspace, toolName, profileName) -> Optional.empty();
  }
}
