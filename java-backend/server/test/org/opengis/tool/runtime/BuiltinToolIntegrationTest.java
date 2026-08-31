/** 文件职责：tool 后端领域：验证对应功能的行为与边界。 */
package org.opengis.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEvent;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.permission.WorkspacePermissionRuleSource;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.skill.FileSystemSkillRepository;
import org.opengis.tool.skill.SkillRepositorySettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class BuiltinToolIntegrationTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path workspace;

  private ToolRegistry registry;
  private ToolRuntime runtime;

  @BeforeEach
  void setUp() {
    registry = BuiltinToolCatalog.registry(mapper);
    runtime = runtime(PermissionRuleSource.empty(), 32_000);
  }

  @Test
  void executesFileWriteReadEditAndEnforcesWorkspaceBoundary() throws Exception {
    ObjectNode write = mapper.createObjectNode();
    write.put("file_path", "notes/demo.txt");
    write.put("content", "alpha\nbeta\n");
    assertThat(execute("write_file", write).success()).isTrue();

    ObjectNode read = mapper.createObjectNode();
    read.put("file_path", "notes/demo.txt");
    assertThat(execute("read_file", read).output().path("output").asString()).contains("alpha");

    ObjectNode edit = mapper.createObjectNode();
    edit.put("file_path", "notes/demo.txt");
    edit.put("old_string", "beta");
    edit.put("new_string", "gamma");
    assertThat(execute("edit_file", edit).success()).isTrue();
    assertThat(Files.readString(workspace.resolve("notes/demo.txt"))).contains("gamma");

    write.put("file_path", workspace.resolve("../escape.txt").normalize().toString());
    assertThat(execute("write_file", write).error().code()).isEqualTo("workspace_boundary");
  }

  @Test
  void rejectionHappensBeforeFileSideEffect() {
    ObjectNode write = mapper.createObjectNode();
    write.put("file_path", "denied.txt");
    write.put("content", "must not exist");
    var result = runtime.execute(new ToolCall("deny", "write_file", write), context(false));
    assertThat(result.error().code()).isEqualTo("permission_rejected");
    assertThat(workspace.resolve("denied.txt")).doesNotExist();
  }

  @Test
  void persistedRuleCanAllowWriteWithoutApproval() throws Exception {
    Path store = workspace.resolve(".opengis/permissions.json");
    Files.createDirectories(store.getParent());
    Files.writeString(
        store,
        "{\"rules\":[{\"id\":\"allow-write\",\"tool\":\"write_file\",\"action\":\"allow\"}]}",
        StandardCharsets.UTF_8);
    ToolRuntime persisted = runtime(new WorkspacePermissionRuleSource(), 32_000);
    ObjectNode write = mapper.createObjectNode();
    write.put("file_path", "allowed.txt");
    write.put("content", "ok");
    var result =
        persisted.execute(
            new ToolCall("persisted", "write_file", write),
            new ToolExecutionContext(
                workspace,
                "test",
                "conversation",
                "test",
                Map.of(),
                PermissionAction.ALLOW,
                new CancellationToken(),
                ignored -> {},
                UiRpcPort.disconnected()));
    assertThat(result.success()).isTrue();
  }

  @Test
  void constrainedPersistedRuleFallsBackToApprovalOutsideAllowedPath() throws Exception {
    Path store = workspace.resolve(".opengis/permissions.json");
    Files.createDirectories(store.getParent());
    Files.writeString(
        store,
        "{\"rules\":[{\"id\":\"data-only\",\"tool\":\"write_file\",\"action\":\"allow\","
            + "\"argument_patterns\":{\"file_path\":\"data/**\"}}]}",
        StandardCharsets.UTF_8);
    ToolRuntime persisted = runtime(new WorkspacePermissionRuleSource(), 32_000);

    ObjectNode allowed = mapper.createObjectNode();
    allowed.put("file_path", "data/allowed.txt");
    allowed.put("content", "ok");
    assertThat(
            persisted
                .execute(new ToolCall("allowed", "write_file", allowed), context(false))
                .success())
        .isTrue();

    ObjectNode outside = mapper.createObjectNode();
    outside.put("file_path", "reports/rejected.txt");
    outside.put("content", "must not be written");
    var rejected =
        persisted.execute(new ToolCall("outside", "write_file", outside), context(false));
    assertThat(rejected.error().code()).isEqualTo("permission_rejected");
    assertThat(workspace.resolve("reports/rejected.txt")).doesNotExist();
  }

  @Test
  void executesArgvShellWithoutSecondaryShell() {
    String executable =
        Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toString();
    ObjectNode args = mapper.createObjectNode();
    args.putArray("argv").add(executable).add("-version");
    args.put("description", "show Java runtime version");
    var result = execute("bash", args);
    assertThat(result.success()).isTrue();
    assertThat(result.output().path("exit_code").asInt()).isZero();
  }

  @Test
  void forwardsUiCommandsAndAcademicInstructions() {
    ObjectNode args = mapper.createObjectNode();
    args.put("layer_id", "roads");
    var mapResult = execute("get_layer", args);
    assertThat(mapResult.output().path("renderer_method").asString())
        .isEqualTo("rpc.ui.map.get_layer");

    var camera = execute("enter_3d_view", mapper.createObjectNode());
    assertThat(camera.output().path("renderer_method").asString())
        .isEqualTo("rpc.ui.map.set_camera");
    assertThat(camera.output().path("renderer_params").path("pitch").asDouble()).isEqualTo(60.0);

    ObjectNode graduated = mapper.createObjectNode();
    graduated.put("layer_id", "districts");
    graduated.put("field", "population");
    graduated.put("method", "equal_interval");
    graduated.put("classes", 5);
    var renderer = execute("set_graduated_style", graduated);
    assertThat(renderer.output().path("renderer_method").asString())
        .isEqualTo("rpc.ui.map.set_layer_renderer");
    assertThat(renderer.output().path("renderer_params").path("renderer").asString())
        .isEqualTo("graduated");
    assertThat(
            renderer.output().path("renderer_params").path("graduated").path("method").asString())
        .isEqualTo("equal-interval");

    ObjectNode academic = mapper.createObjectNode();
    academic.put("text", "A GIS result.");
    var writing = execute("academic_polish", academic);
    assertThat(writing.output().path("action").asString()).isEqualTo("polish");
  }

  @Test
  void discoversAndLoadsSkillsOnDemandAcrossWorkspaceRoots() throws Exception {
    Path skill = workspace.resolve("skills/gis-review/SKILL.md");
    Files.createDirectories(skill.getParent());
    Files.writeString(
        skill,
        "---\nname: gis-review\ndescription: Review GIS outputs\ntags: [gis, review]\n---\n"
            + "Read references/checks.md when detailed rules are needed.",
        StandardCharsets.UTF_8);
    Files.createDirectories(skill.getParent().resolve("references"));
    Files.writeString(
        skill.getParent().resolve("references/checks.md"),
        "Check the CRS first.",
        StandardCharsets.UTF_8);

    ObjectNode list = mapper.createObjectNode();
    list.put("query", "gis");
    var discovered = execute("list_skills", list);
    assertThat(discovered.success()).isTrue();
    assertThat(discovered.output().path("skills").get(0).path("name").asString())
        .isEqualTo("gis-review");

    ObjectNode load = mapper.createObjectNode();
    load.put("name", "gis-review");
    var loaded = execute("load_skill", load);
    assertThat(loaded.success()).isTrue();
    assertThat(loaded.output().path("content").asString()).contains("references/checks.md");

    var resources = execute("list_skill_resources", load);
    assertThat(resources.success()).isTrue();
    assertThat(resources.output().path("resources").get(0).path("path").asString())
        .isEqualTo("references/checks.md");

    ObjectNode readResource = mapper.createObjectNode();
    readResource.put("name", "gis-review");
    readResource.put("path", "references/checks.md");
    var reference = execute("read_skill_resource", readResource);
    assertThat(reference.success()).isTrue();
    assertThat(reference.output().path("content").asString()).contains("Check the CRS first");
  }

  @Test
  void pagesSkillResourcesAndEnforcesThePerRunCharacterBudget() throws Exception {
    Path skill = workspace.resolve("skills/paged");
    Files.createDirectories(skill.resolve("references"));
    Files.writeString(skill.resolve("SKILL.md"), "---\nname: paged\n---\nPaged reference");
    Files.writeString(skill.resolve("references/large.md"), "abcdefghijkl");
    SkillRepositorySettings settings = new SkillRepositorySettings(1024, 4096, 6, 10, 10);
    registry = BuiltinToolCatalog.registry(mapper, new FileSystemSkillRepository(settings));
    runtime = runtime(PermissionRuleSource.empty(), 32_000);

    ObjectNode arguments = mapper.createObjectNode();
    arguments.put("name", "paged");
    arguments.put("path", "references/large.md");
    arguments.put("max_chars", 6);
    var first = execute("read_skill_resource", arguments);
    assertThat(first.success()).isTrue();
    assertThat(first.output().path("content").asString()).isEqualTo("abcdef");
    assertThat(first.output().path("next_offset").asInt()).isEqualTo(6);
    assertThat(first.output().path("truncated").asBoolean()).isTrue();

    arguments.put("offset", 6);
    var second = execute("read_skill_resource", arguments);
    assertThat(second.success()).isFalse();
    assertThat(second.error().code()).isEqualTo("skill_run_resource_budget_exceeded");
  }

  @Test
  void writesReportAndConvertsQuotedCsvToGeoJson() throws Exception {
    ObjectNode report = mapper.createObjectNode();
    report.put("output_dir", "report");
    report.put("title", "Study");
    report.put("heading", "Results");
    report.put("content", "Complete.");
    assertThat(execute("write_report_section", report).success()).isTrue();
    assertThat(workspace.resolve("report/report.md")).content().contains("Results");

    Files.writeString(
        workspace.resolve("points.csv"),
        "name,lat,lng\n\"A, B\",22.3,114.2\n",
        StandardCharsets.UTF_8);
    ObjectNode csv = mapper.createObjectNode();
    csv.put("input_path", "points.csv");
    var converted = execute("csv_to_geojson", csv);
    assertThat(converted.success()).isTrue();
    assertThat(converted.output().path("feature_count").asInt()).isEqualTo(1);
    assertThat(workspace.resolve("points.geojson")).exists();
  }

  @Test
  void truncatesLargeOutputMaterializesArtifactAndEmitsLifecycle() throws Exception {
    ToolDefinition definition =
        new ToolDefinition(
            "large",
            "Large",
            "Large output probe",
            "test",
            "test",
            "1.0.0",
            ToolRisk.READ,
            mapper.createObjectNode().put("type", "object"),
            List.of());
    OpenGisTool tool =
        new OpenGisTool() {
          @Override
          public ToolDefinition definition() {
            return definition;
          }

          @Override
          public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
            return mapper.valueToTree(
                Map.of("content", "x".repeat(2_000), "api_key", "artifact-secret-value"));
          }
        };
    List<ToolEvent> events = new ArrayList<>();
    ToolRuntime small =
        new ToolRuntime(
            new ToolRegistry().register(tool),
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper,
            256);
    ToolExecutionContext context =
        new ToolExecutionContext(
            workspace,
            "artifact-run",
            "conversation",
            "test",
            Map.of(),
            PermissionAction.ALLOW,
            new CancellationToken(),
            events::add,
            approvingUi());
    var result = small.execute(new ToolCall("large", "large", mapper.createObjectNode()), context);
    assertThat(result.truncated()).isTrue();
    assertThat(result.artifacts()).hasSize(1);
    Path artifact = workspace.resolve(result.artifacts().getFirst().path());
    assertThat(artifact).exists();
    assertThat(Files.readString(artifact))
        .contains("[REDACTED]")
        .doesNotContain("artifact-secret-value");
    assertThat(events)
        .extracting(ToolEvent::type)
        .contains("tool.started", "tool.artifact", "tool.completed");
  }

  private org.opengis.tool.api.ToolResult execute(String name, JsonNode arguments) {
    return runtime.execute(new ToolCall("integration", name, arguments), context(true));
  }

  private ToolRuntime runtime(PermissionRuleSource rules, int outputLimit) {
    return new ToolRuntime(
        registry,
        new JsonSchemaValidator(),
        new PermissionRuntime(rules),
        new ArtifactMaterializer(),
        mapper,
        outputLimit);
  }

  private ToolExecutionContext context(boolean approved) {
    return new ToolExecutionContext(
        workspace,
        "test",
        "conversation",
        "test",
        Map.of(),
        PermissionAction.ALLOW,
        new CancellationToken(),
        ignored -> {},
        approved ? approvingUi() : rejectingUi());
  }

  private UiRpcPort approvingUi() {
    return ui(true);
  }

  private UiRpcPort rejectingUi() {
    return ui(false);
  }

  private UiRpcPort ui(boolean approved) {
    return new UiRpcPort() {
      @Override
      public java.util.concurrent.CompletionStage<JsonNode> request(
          String method, JsonNode params, Duration timeout) {
        if (method.startsWith("rpc.ui.ask.")) {
          return CompletableFuture.completedFuture(
              mapper.valueToTree(Map.of("approved", approved)));
        }
        return CompletableFuture.completedFuture(
            mapper.valueToTree(Map.of("renderer_method", method, "renderer_params", params)));
      }

      @Override
      public void notify(String method, JsonNode params) {}
    };
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
