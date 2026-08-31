/** 文件职责：agent 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opengis.assistant.agent.profile.AgentProfile.AgentMode;
import org.opengis.assistant.agent.profile.AgentProfile;
import org.opengis.assistant.agent.profile.AgentProfile.PermissionLevel;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.registry.ToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolSchemaProjectorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void coreProfileDoesNotAccidentallyExposeEveryToolGroup() {
    ToolRegistry registry =
        new ToolRegistry()
            .register(tool("read_file", "core"))
            .register(tool("qgis_buffer", "qgis"));
    AgentProfile profile =
        new AgentProfile(
            "core-only",
            AgentMode.PLAN,
            "Core only",
            List.of("core"),
            PermissionLevel.READ_ONLY,
            8,
            false,
            "",
            Map.of("max_provider_turns", 8, "max_tool_steps", 12));

    assertThat(new ToolSchemaProjector().project(registry, profile))
        .extracting(definition -> definition.name())
        .containsExactly("read_file");
  }

  private OpenGisTool tool(String name, String group) {
    ToolDefinition definition =
        new ToolDefinition(
            name,
            name,
            "Test tool",
            "test",
            group,
            "1",
            ToolRisk.READ,
            mapper.createObjectNode().put("type", "object"),
            List.of());
    return new OpenGisTool() {
      @Override
      public ToolDefinition definition() {
        return definition;
      }

      @Override
      public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
        return mapper.createObjectNode();
      }
    };
  }
}
