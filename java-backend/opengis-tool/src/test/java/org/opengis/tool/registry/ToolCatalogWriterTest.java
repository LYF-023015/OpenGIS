package org.opengis.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolCatalogWriterTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void writesOneSortedCatalogFromTheEffectiveRegistry(@TempDir Path workspace) {
    ToolRegistry registry =
        new ToolRegistry()
            .register(tool("write_file", ToolRisk.WRITE))
            .register(tool("bash", ToolRisk.PROCESS));

    Path path = new ToolCatalogWriter(registry, mapper).write(workspace);
    JsonNode catalog = new org.opengis.platform.persistence.JsonFileStore(mapper).read(path);

    assertThat(path).isEqualTo(workspace.resolve("tool-catalog.json"));
    assertThat(catalog.path("total").asInt()).isEqualTo(2);
    assertThat(
            catalog.path("tools").valueStream().map(node -> node.path("name").asString()).toList())
        .containsExactly("bash", "write_file");
    assertThat(catalog.path("tools").get(0).path("input_schema").isObject()).isTrue();
  }

  private OpenGisTool tool(String name, ToolRisk risk) {
    ToolDefinition definition =
        new ToolDefinition(
            name,
            name,
            "Test tool",
            "test",
            "core",
            "1",
            risk,
            mapper.createObjectNode().put("type", "object"),
            List.of("test"));
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
