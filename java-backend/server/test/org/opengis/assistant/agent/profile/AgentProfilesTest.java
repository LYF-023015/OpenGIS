/** 文件职责：agent 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.agent.profile;

import org.opengis.assistant.agent.profile.AgentProfile.PermissionLevel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.assistant.agent.persistence.AgentProfileStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentProfilesTest {
  @Test
  void builtInProfilesExposeExplicitProviderAndToolBudgets() {
    assertBudgets("gis-plan", 8, 12);
    assertBudgets("gis-explore", 12, 24);
    assertBudgets("gis-build", 30, 60);
    assertBudgets("workflow-runner", 16, 32);
    assertBudgets("gis-subagent", 10, 20);
    assertThat(AgentProfiles.defaultsList())
        .allSatisfy(profile -> assertThat(profile.limits()).doesNotContainKey("max_work_steps"));
    assertThat(AgentProfile.gisBuild().toolGroups())
        .containsExactly("core", "gis", "operation", "code", "worker", "report");
    assertThat(AgentProfile.gisExplore().toolGroups()).containsExactly("core", "gis", "report");
  }

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

  private static void assertBudgets(String name, int providerTurns, int toolSteps) {
    AgentProfile profile = AgentProfiles.get(name);
    assertThat(profile.maxSteps()).isEqualTo(providerTurns);
    assertThat(profile.limits())
        .containsAllEntriesOf(
            Map.of("max_provider_turns", providerTurns, "max_tool_steps", toolSteps));
  }
}
