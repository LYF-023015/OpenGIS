/** 文件职责：tool 后端领域：验证对应功能的行为与边界。 */
package org.opengis.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opengis.core.plugin.PluginHandle;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolRegistryPluginTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void contributionIsVisibleUntilItsHandleCloses() {
    ToolRegistry registry = new ToolRegistry();
    OpenGisTool tool = tool("sample");

    PluginHandle handle = registry.contribute(tool);
    assertThat(registry.find("sample")).containsSame(tool);

    handle.close();
    handle.close();
    assertThat(registry.find("sample")).isEmpty();
  }

  @Test
  void groupedContributionRollsBackWhenOneNameIsDuplicated() {
    ToolRegistry registry = new ToolRegistry().register(tool("existing"));

    assertThatThrownBy(() -> registry.contributeAll(List.of(tool("temporary"), tool("existing"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing");
    assertThat(registry.find("temporary")).isEmpty();
    assertThat(registry.find("existing")).isPresent();
  }

  private OpenGisTool tool(String name) {
    JsonNode schema = mapper.valueToTree(Map.of("type", "object"));
    return new OpenGisTool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            name, name, "test", "test", "test", "1.0.0", ToolRisk.READ, schema, List.of());
      }

      @Override
      public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
        return mapper.createObjectNode();
      }
    };
  }
}
