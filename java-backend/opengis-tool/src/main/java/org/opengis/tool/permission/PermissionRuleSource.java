package org.opengis.tool.permission;

import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface PermissionRuleSource {
  Optional<PermissionDecision> match(Path workspace, String toolName, String profileName);

  static PermissionRuleSource empty() {
    return (workspace, toolName, profileName) -> Optional.empty();
  }
}
