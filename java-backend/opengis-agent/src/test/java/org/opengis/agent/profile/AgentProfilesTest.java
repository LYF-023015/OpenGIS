package org.opengis.agent.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.persistence.AgentProfileStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentProfilesTest {
  @Test
  void workspaceProfileOverlaysBuiltInRuntimeLimits(@TempDir Path workspace) {
    ObjectNode custom = new ObjectMapper().createObjectNode();
    custom.put("name", "gis-build");
    custom.put("mode", "build");
    custom.put("description", "Workspace profile");
    custom.put("permission_level", "read_only");
    custom.put("max_steps", 5);
    custom.putArray("tool_groups").add("core");
    custom.putObject("metadata").put("max_provider_turns", 4);
    new AgentProfileStore(workspace).save(List.of(custom));

    AgentProfile profile = AgentProfiles.resolve(workspace, "gis-build");

    assertThat(profile.description()).isEqualTo("Workspace profile");
    assertThat(profile.permissionLevel()).isEqualTo(PermissionLevel.READ_ONLY);
    assertThat(profile.maxSteps()).isEqualTo(5);
    assertThat(profile.limit("max_provider_turns", 99)).isEqualTo(4);
  }
}
