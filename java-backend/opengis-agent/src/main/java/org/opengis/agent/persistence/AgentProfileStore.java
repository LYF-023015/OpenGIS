package org.opengis.agent.persistence;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Compatible reader/writer for workspace-defined agent profiles with Python defaults. */
public class AgentProfileStore {
  private final JsonFileStore files;
  private final Path path;

  public AgentProfileStore(Path workspaceRoot) {
    this.files = new JsonFileStore();
    this.path = new WorkspaceLayout(workspaceRoot).resolve("agents.json");
  }

  public List<ObjectNode> list() {
    Map<String, ObjectNode> profiles = new LinkedHashMap<>();
    defaults().forEach(profile -> profiles.put(profile.path("name").asString(), profile));
    ObjectNode root = files.readObject(path);
    if (root.path("profiles").isArray()) {
      root.path("profiles")
          .valueStream()
          .filter(JsonNode::isObject)
          .map(node -> (ObjectNode) node)
          .forEach(profile -> profiles.put(profile.path("name").asString(), profile));
    }
    return List.copyOf(profiles.values());
  }

  public void save(List<ObjectNode> profiles) {
    ObjectNode root = files.objectMapper().createObjectNode();
    ArrayNode values = root.putArray("profiles");
    profiles.forEach(values::add);
    files.write(path, root);
  }

  private List<ObjectNode> defaults() {
    return List.of(
        profile("gis-build", "build", "Default autonomous GIS task execution agent.", "safe_write"),
        profile("gis-plan", "plan", "Read-only planning and decomposition agent.", "read_only"),
        profile(
            "gis-explore",
            "explore",
            "Dataset exploration agent with bounded output.",
            "read_only"),
        profile(
            "workflow-runner",
            "workflow",
            "Structured workflow node execution agent.",
            "safe_write"),
        profile(
            "gis-subagent",
            "subagent",
            "Isolated child agent for a self-contained subtask.",
            "safe_write"));
  }

  private ObjectNode profile(String name, String mode, String description, String permissionLevel) {
    ObjectNode profile = files.objectMapper().createObjectNode();
    profile.put("name", name);
    profile.put("mode", mode);
    profile.put("description", description);
    profile.put("permission_level", permissionLevel);
    profile.putNull("tool_groups");
    profile.putNull("max_steps");
    profile.put("hidden", false);
    profile.put("prompt_suffix", "");
    profile.putObject("metadata");
    return profile;
  }
}
