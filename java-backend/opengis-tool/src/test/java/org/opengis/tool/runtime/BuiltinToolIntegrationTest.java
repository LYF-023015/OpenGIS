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
    assertThat(execute("read_file", read).output().path("output").asText()).contains("alpha");

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
    assertThat(mapResult.output().path("renderer_method").asText())
        .isEqualTo("rpc.ui.map.get_layer");

    var camera = execute("enter_3d_view", mapper.createObjectNode());
    assertThat(camera.output().path("renderer_method").asText()).isEqualTo("rpc.ui.map.set_camera");
    assertThat(camera.output().path("renderer_params").path("pitch").asDouble()).isEqualTo(60.0);

    ObjectNode graduated = mapper.createObjectNode();
    graduated.put("layer_id", "districts");
    graduated.put("field", "population");
    graduated.put("method", "equal_interval");
    graduated.put("classes", 5);
    var renderer = execute("set_graduated_style", graduated);
    assertThat(renderer.output().path("renderer_method").asText())
        .isEqualTo("rpc.ui.map.set_layer_renderer");
    assertThat(renderer.output().path("renderer_params").path("renderer").asText())
        .isEqualTo("graduated");
    assertThat(renderer.output().path("renderer_params").path("graduated").path("method").asText())
        .isEqualTo("equal-interval");

    ObjectNode academic = mapper.createObjectNode();
    academic.put("text", "A GIS result.");
    var writing = execute("academic_polish", academic);
    assertThat(writing.output().path("action").asText()).isEqualTo("polish");
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
  void truncatesLargeOutputMaterializesArtifactAndEmitsLifecycle() {
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
            return mapper.valueToTree(Map.of("content", "x".repeat(2_000)));
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
    assertThat(workspace.resolve(result.artifacts().getFirst().path())).exists();
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
