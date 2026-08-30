package org.opengis.tool.permission;

import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;

/** Priority: persisted rule -> profile override -> risk rule -> profile default. */
public final class PermissionRuntime {
  private final PermissionRuleSource persistedRules;

  public PermissionRuntime(PermissionRuleSource persistedRules) {
    this.persistedRules = persistedRules == null ? PermissionRuleSource.empty() : persistedRules;
  }

  public PermissionDecision decide(ToolDefinition tool, ToolExecutionContext context) {
    return decide(tool, null, context);
  }

  public PermissionDecision decide(
      ToolDefinition tool, JsonNode arguments, ToolExecutionContext context) {
    var persisted =
        persistedRules.match(context.workspace(), tool.name(), context.profileName(), arguments);
    if (persisted.isPresent()) {
      return persisted.get();
    }
    PermissionAction override = context.profileOverrides().get(tool.name());
    if (override != null) {
      return new PermissionDecision(override, "Matched profile override", "profile:" + tool.name());
    }
    if (tool.risk() == ToolRisk.DESTRUCTIVE) {
      return new PermissionDecision(
          PermissionAction.ASK, "Destructive tools require confirmation", "risk:destructive");
    }
    if (tool.risk() == ToolRisk.WRITE || tool.risk() == ToolRisk.NETWORK) {
      return new PermissionDecision(
          PermissionAction.ASK, "Side-effecting tool requires confirmation", "risk:side_effect");
    }
    return new PermissionDecision(
        context.defaultPermission(), "Profile default permission", "default");
  }
}
