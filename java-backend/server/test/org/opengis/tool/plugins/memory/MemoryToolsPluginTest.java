/** 文件职责：plugins 后端领域：验证对应功能的行为与边界。 */
package org.opengis.tool.plugins.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.core.plugin.PluginContext;
import org.opengis.core.plugin.PluginRuntime;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolCatalogPlugin;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MemoryToolsPluginTest {
  @Test
  void contributesAndExecutesTheCompleteMemoryLifecycle(@TempDir Path workspace) {
    ObjectMapper mapper = new ObjectMapper();
    ToolRegistry registry = new ToolRegistry();
    new PluginRuntime(
            PluginContext.builder().service(ToolRegistry.class, registry).build(),
            java.util.List.of(
                new ToolCatalogPlugin(
                    org.opengis.core.plugin.PluginDescriptor.of("core-tools"),
                    java.util.List::of),
                new MemoryToolsPlugin(mapper)),
            new org.opengis.core.plugin.PluginProfile(
                "test", java.util.Set.of("memory-tools")))
        .start();
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    ToolExecutionContext context =
        new ToolExecutionContext(
            workspace,
            "test",
            "conversation",
            "test",
            Map.of(),
            PermissionAction.ALLOW,
            new CancellationToken(),
            ignored -> {},
            new UiRpcPort() {
              @Override
              public java.util.concurrent.CompletionStage<JsonNode> request(
                  String method, JsonNode params, Duration timeout) {
                return CompletableFuture.completedFuture(
                    mapper.valueToTree(Map.of("approved", true)));
              }

              @Override
              public void notify(String method, JsonNode params) {}
            });

    ObjectNode remember = mapper.createObjectNode();
    remember.put("kind", "FACT");
    remember.put("content", "项目道路图层使用 EPSG:4326 坐标系");
    remember.put("scope", "WORKSPACE");
    JsonNode created = execute(runtime, context, mapper, "remember", remember);
    String id = created.path("id").asString();

    ObjectNode list = mapper.createObjectNode().put("query", "EPSG 道路坐标系");
    assertThat(execute(runtime, context, mapper, "list_memories", list).path("memories"))
        .isNotEmpty();

    ObjectNode update = mapper.createObjectNode().put("id", id).put("importance", 0.95);
    assertThat(
            execute(runtime, context, mapper, "update_memory", update)
                .path("importance")
                .asDouble())
        .isEqualTo(0.95);
    assertThat(
            execute(
                    runtime,
                    context,
                    mapper,
                    "delete_memory",
                    mapper.createObjectNode().put("id", id))
                .path("deleted")
                .asBoolean())
        .isTrue();
  }

  private static JsonNode execute(
      ToolRuntime runtime,
      ToolExecutionContext context,
      ObjectMapper mapper,
      String name,
      JsonNode arguments) {
    var result = runtime.execute(new ToolCall("integration", name, arguments), context);
    assertThat(result.success()).as(name + " result: " + result.error()).isTrue();
    return result.output() == null ? mapper.createObjectNode() : result.output();
  }
}
