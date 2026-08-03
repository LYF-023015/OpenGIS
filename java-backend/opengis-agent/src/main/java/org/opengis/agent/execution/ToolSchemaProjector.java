package org.opengis.agent.execution;

import java.util.List;
import java.util.Set;
import org.opengis.agent.profile.AgentProfile;
import org.opengis.ai.model.LlmToolDefinition;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.registry.ToolRegistry;

/** Stable AgentProfile tool surface projected into neutral provider schemas. */
public final class ToolSchemaProjector {
  public List<LlmToolDefinition> project(ToolRegistry registry, AgentProfile profile) {
    Set<String> groups = Set.copyOf(profile.toolGroups());
    return registry.definitions().stream()
        .filter(
            definition ->
                groups.isEmpty() || groups.contains(definition.group()) || groups.contains("core"))
        .map(ToolSchemaProjector::project)
        .toList();
  }

  private static LlmToolDefinition project(ToolDefinition definition) {
    return new LlmToolDefinition(
        definition.name(), definition.description(), definition.inputSchema());
  }
}
