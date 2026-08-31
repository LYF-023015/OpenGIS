/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import tools.jackson.databind.ObjectMapper;

class AgentApplicationServiceSkillPromptTest {
  @TempDir Path workspace;

  @Test
  void injectsCompactSkillMetadataAndTheProgressiveLoadingProtocol() throws Exception {
    Path skill = workspace.resolve(".opengis/skills/gis-review/SKILL.md");
    Files.createDirectories(skill.getParent());
    Files.writeString(
        skill,
        "---\nname: gis-review\ndescription: Review GIS outputs\ntags: [gis, review]\n---\n"
            + "SECRET BODY THAT MUST BE LOADED ON DEMAND",
        StandardCharsets.UTF_8);
    ObjectMapper mapper = new ObjectMapper();
    AgentApplicationService service =
        new AgentApplicationService(
            null, null, null, null, BuiltinToolCatalog.registry(mapper), null, null, null, mapper);

    assertThat(service.capabilityManifest(workspace))
        .contains("gis-review", "Review GIS outputs", "tags=[gis, review]")
        .doesNotContain("SECRET BODY");
    assertThat(AgentApplicationService.toolProtocol())
        .contains("load_skill", "list_skill_resources", "read_skill_resource")
        .contains("Reading a script never authorizes execution");
  }
}
