package org.opengis.agent.profile;

import java.util.List;
import java.util.Map;

/** Control-plane identity governing tools, permissions, and bounded autonomy. */
public record AgentProfile(
    String name,
    AgentMode mode,
    String description,
    List<String> toolGroups,
    PermissionLevel permissionLevel,
    int maxSteps,
    boolean hidden,
    String promptSuffix,
    Map<String, Integer> limits) {
  public AgentProfile {
    if (name == null || name.isBlank() || mode == null) {
      throw new IllegalArgumentException("Profile name and mode are required");
    }
    description = description == null ? "" : description;
    toolGroups = toolGroups == null ? List.of() : List.copyOf(toolGroups);
    permissionLevel = permissionLevel == null ? PermissionLevel.SAFE_WRITE : permissionLevel;
    maxSteps = maxSteps <= 0 ? 12 : maxSteps;
    promptSuffix = promptSuffix == null ? "" : promptSuffix;
    limits = limits == null ? Map.of() : Map.copyOf(limits);
  }

  public int limit(String key, int fallback) {
    return Math.max(1, limits.getOrDefault(key, fallback));
  }

  public static AgentProfile gisBuild() {
    return new AgentProfile(
        "gis-build",
        AgentMode.BUILD,
        "Default autonomous GIS task execution agent.",
        List.of("core", "qgis", "osm", "datasource", "worker"),
        PermissionLevel.SAFE_WRITE,
        12,
        false,
        "",
        Map.of("max_provider_turns", 30, "max_tool_steps", 60, "max_work_steps", 60));
  }

  public static AgentProfile gisPlan() {
    return new AgentProfile(
        "gis-plan",
        AgentMode.PLAN,
        "Read-only planning and decomposition agent.",
        List.of("core"),
        PermissionLevel.READ_ONLY,
        3,
        false,
        "\nPlanning mode: read and reason; do not mutate files or map state.\n",
        Map.of("max_provider_turns", 3, "max_tool_steps", 3, "max_work_steps", 3));
  }

  public static AgentProfile gisExplore() {
    return new AgentProfile(
        "gis-explore",
        AgentMode.EXPLORE,
        "Dataset exploration agent with bounded output.",
        List.of("core", "datasource", "osm"),
        PermissionLevel.READ_ONLY,
        4,
        false,
        "",
        Map.of("max_provider_turns", 4, "max_tool_steps", 8, "max_work_steps", 8));
  }
}
