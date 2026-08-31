/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.permission.WorkspacePermissionRuleSource;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.tool.skill.FileSystemSkillRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolRpcMethodsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path workspace;

  private RpcDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    RpcMethodRegistry methods = new RpcMethodRegistry();
    ToolRegistry tools = BuiltinToolCatalog.registry(mapper);
    ToolRuntime runtime =
        new ToolRuntime(
            tools,
            new JsonSchemaValidator(),
            new PermissionRuntime(new WorkspacePermissionRuleSource()),
            new ArtifactMaterializer(),
            mapper);
    new CoreRpcMethods(methods).registerMethods();
    new ToolRpcMethods(methods, tools, runtime, mock(UiRpcGateway.class), mapper).registerMethods();
    new UserSkillRpcMethods(methods, new FileSystemSkillRepository()).registerMethods();
    dispatcher = new RpcDispatcher(mapper, methods);
  }

  @Test
  void exposesPythonCompatibleCatalogAndExecutesReadTool() throws Exception {
    Files.writeString(workspace.resolve("sample.txt"), "phase4", StandardCharsets.UTF_8);
    JsonNode catalog = call("rpc.tool.list", "{}");
    assertThat(catalog.path("result").path("tools").size()).isGreaterThan(50);
    JsonNode first = catalog.path("result").path("tools").get(0);
    assertThat(first.has("display_name")).isTrue();
    assertThat(first.has("params")).isTrue();

    String params =
        "{\"workspace_path\":"
            + quote(workspace.toString())
            + ",\"name\":\"read_file\",\"args\":{\"file_path\":\"sample.txt\"}}";
    JsonNode result = call("rpc.tool.execute", params).path("result");
    assertThat(result.path("success").asBoolean()).isTrue();
    assertThat(result.path("data").path("output").asString()).contains("phase4");
  }

  @Test
  void preservesInvalidParamsAndImplementsFileRpc() throws Exception {
    assertThat(call("rpc.tool.execute", "{}").path("error").path("code").asInt()).isEqualTo(-32602);
    Files.writeString(workspace.resolve("rpc.txt"), "content", StandardCharsets.UTF_8);
    String params = "{\"workspace_path\":" + quote(workspace.toString()) + ",\"path\":\"rpc.txt\"}";
    assertThat(call("rpc.fs.load_file", params).path("result").path("content").asString())
        .isEqualTo("content");
    assertThat(call("rpc.fs.get_file_info", params).path("result").path("is_file").asBoolean())
        .isTrue();
  }

  @Test
  void listsWorkspaceSkillsWithoutBreakingTheToolCatalog() throws Exception {
    Path skillDirectory = workspace.resolve(".opengis/skills/gis-review");
    Files.createDirectories(skillDirectory);
    Files.writeString(
        skillDirectory.resolve("SKILL.md"),
        "---\nname: gis-review\ndescription: Review GIS outputs\ntags: [gis, review]\n---\nInstructions",
        StandardCharsets.UTF_8);

    String params = "{\"workspace_path\":" + quote(workspace.toString()) + "}";
    JsonNode result = call("rpc.user_skill.list", params).path("result").path("skills");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).path("name").asString()).isEqualTo("gis-review");
    assertThat(result.get(0).path("tags").valueStream().map(JsonNode::asString).toList())
        .containsExactly("gis", "review");
    assertThat(call("rpc.tool.list", "{}").path("result").path("tools")).isNotEmpty();
  }

  private JsonNode call(String method, String params) {
    String payload =
        "{\"jsonrpc\":\"2.0\",\"id\":\"phase4\",\"method\":"
            + quote(method)
            + ",\"params\":"
            + params
            + "}";
    return mapper.valueToTree(dispatcher.dispatch(payload));
  }

  private String quote(String value) {
    return mapper.writeValueAsString(value);
  }
}
