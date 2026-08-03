package org.opengis.agent.profile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opengis.agent.persistence.AgentProfileStore;
import tools.jackson.databind.JsonNode;

public final class AgentProfiles {
  private static final Map<String, AgentProfile> DEFAULTS = defaults();

  private AgentProfiles() {}

  public static AgentProfile get(String name) {
    return DEFAULTS.getOrDefault(name, DEFAULTS.get("gis-build"));
  }

  public static List<AgentProfile> defaultsList() {
    return List.copyOf(DEFAULTS.values());
  }

  /** Applies a workspace agents.json overlay while retaining safe built-in defaults. */
  public static AgentProfile resolve(Path workspace, String name) {
    AgentProfile base = get(name);
    JsonNode overlay =
        new AgentProfileStore(workspace)
            .list().stream()
                .filter(node -> name.equals(node.path("name").asText()))
                .findFirst()
                .orElse(null);
    if (overlay == null) {
      return base;
    }
    String resolvedName = overlay.path("name").asText(base.name());
    AgentMode mode = parse(AgentMode.class, overlay.path("mode").asText(), base.mode());
    PermissionLevel permission =
        parse(
            PermissionLevel.class,
            overlay.path("permission_level").asText(),
            base.permissionLevel());
    List<String> groups =
        overlay.path("tool_groups").isArray()
            ? overlay.path("tool_groups").valueStream().map(JsonNode::asText).toList()
            : base.toolGroups();
    Map<String, Integer> limits = new LinkedHashMap<>(base.limits());
    if (overlay.path("metadata").isObject()) {
      overlay
          .path("metadata")
          .properties()
          .forEach(
              entry -> {
                if (entry.getValue().isIntegralNumber()) {
                  limits.put(entry.getKey(), entry.getValue().asInt());
                }
              });
    }
    return new AgentProfile(
        resolvedName,
        mode,
        overlay.path("description").asText(base.description()),
        groups,
        permission,
        overlay.path("max_steps").asInt(base.maxSteps()),
        overlay.path("hidden").asBoolean(base.hidden()),
        overlay.path("prompt_suffix").asText(base.promptSuffix()),
        limits);
  }

  private static <T extends Enum<T>> T parse(Class<T> type, String value, T fallback) {
    try {
      return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return fallback;
    }
  }

  private static Map<String, AgentProfile> defaults() {
    Map<String, AgentProfile> profiles = new LinkedHashMap<>();
    profiles.put("gis-build", AgentProfile.gisBuild());
    profiles.put("gis-plan", AgentProfile.gisPlan());
    profiles.put("gis-explore", AgentProfile.gisExplore());
    profiles.put(
        "workflow-runner",
        new AgentProfile(
            "workflow-runner",
            AgentMode.WORKFLOW,
            "Structured workflow node execution agent.",
            List.of(),
            PermissionLevel.SAFE_WRITE,
            8,
            false,
            "",
            Map.of("max_provider_turns", 8)));
    profiles.put(
        "gis-subagent",
        new AgentProfile(
            "gis-subagent",
            AgentMode.SUBAGENT,
            "Isolated child agent for a self-contained subtask.",
            List.of(),
            PermissionLevel.SAFE_WRITE,
            4,
            false,
            "",
            Map.of("max_provider_turns", 4)));
    return Map.copyOf(profiles);
  }
}
