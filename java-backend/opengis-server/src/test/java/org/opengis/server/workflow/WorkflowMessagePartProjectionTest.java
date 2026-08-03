package org.opengis.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.workflow.WorkflowCodec;
import org.opengis.workflow.execution.WorkflowRunStore;
import org.opengis.workflow.persistence.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkflowMessagePartProjectionTest {
  @Test
  void runArchiveChatAndPlanReceiveOneConsistentProjection(@TempDir Path workspace)
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ToolRegistry registry = new ToolRegistry().register(new EchoTool(mapper));
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    UiRpcGateway ui = mock(UiRpcGateway.class);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      WorkflowApplicationService service =
          new WorkflowApplicationService(
              executor, mock(AgentApplicationService.class), runtime, ui, mapper);
      var workflow =
          new WorkflowCodec(mapper)
              .parse(
                  """
          {"schemaVersion":2,"id":"projection","name":"Projection","nodes":[
            {"id":"echo","title":"Echo","type":"tool_call","execution":{"kind":"tool_call","ref":"echo"},"inputs":[],"outputs":[],"params":{"value":"ok"},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
          ],"edges":[]}
          """);
      String runId =
          String.valueOf(
              service.start(workspace, workflow, "chat", "connection", false, "").get("run_id"));
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!"completed".equals(service.status(workspace, runId)) && System.nanoTime() < deadline)
        Thread.sleep(20);

      assertThat(service.status(workspace, runId)).isEqualTo("completed");
      RunArchive archive = RunArchive.load(workspace, runId).orElseThrow();
      assertThat(archive.meta().path("status").asText()).isEqualTo("completed");
      assertThat(archive.read("message_parts.jsonl"))
          .isNotEmpty()
          .allSatisfy(part -> assertThat(part.path("type").asText()).isEqualTo("plan"));
      JsonNode finalPart = archive.read("message_parts.jsonl").getLast();
      assertThat(finalPart.path("status").asText()).isEqualTo("completed");
      assertThat(
              finalPart.path("data").path("planData").path("steps").get(0).path("status").asText())
          .isEqualTo("completed");
      verify(ui, atLeastOnce()).notify(eq("connection"), eq("chat.message_part"), anyMap());
    }
  }

  @Test
  void subworkflowExecutesPersistedChildAndRejectsCycles(@TempDir Path workspace) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ToolRegistry registry = new ToolRegistry().register(new EchoTool(mapper));
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    var child =
        new WorkflowCodec(mapper)
            .parse(
                """
          {"schemaVersion":2,"id":"child","name":"Child","nodes":[
            {"id":"echo","title":"Echo","type":"tool_call","execution":{"kind":"tool_call","ref":"echo"},"inputs":[],"outputs":[],"params":{"value":"nested"},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
          ],"edges":[]}
          """);
    var parent =
        new WorkflowCodec(mapper)
            .parse(
                """
          {"schemaVersion":2,"id":"parent","name":"Parent","nodes":[
            {"id":"nested","title":"Nested","type":"subworkflow","execution":{"kind":"subworkflow","ref":"child"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
          ],"edges":[]}
          """);
    new WorkflowStore(workspace).save(child);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      WorkflowApplicationService service =
          new WorkflowApplicationService(
              executor,
              mock(AgentApplicationService.class),
              runtime,
              mock(UiRpcGateway.class),
              mapper);
      String runId =
          String.valueOf(
              service.start(workspace, parent, "chat", "", false, "parent-run").get("run_id"));
      awaitTerminal(service, workspace, runId);
      var parentRun = new WorkflowRunStore(workspace).load(runId).orElseThrow();
      assertThat(parentRun.status()).isEqualTo("completed");
      String childRunId = parentRun.nodes().get("nested").childRunId();
      assertThat(childRunId).isNotBlank();
      assertThat(new WorkflowRunStore(workspace).load(childRunId).orElseThrow().status())
          .isEqualTo("completed");

      var cyclic =
          new WorkflowCodec(mapper)
              .parse(
                  """
            {"schemaVersion":2,"id":"cyclic","name":"Cyclic","nodes":[
              {"id":"self","title":"Self","type":"subworkflow","execution":{"kind":"subworkflow","ref":"cyclic"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
            ],"edges":[]}
            """);
      new WorkflowStore(workspace).save(cyclic);
      String cyclicRun =
          String.valueOf(
              service.start(workspace, cyclic, "chat", "", false, "cycle-run").get("run_id"));
      awaitTerminal(service, workspace, cyclicRun);
      var failed = new WorkflowRunStore(workspace).load(cyclicRun).orElseThrow();
      assertThat(failed.status()).isEqualTo("failed");
      assertThat(failed.error()).contains("Recursive subworkflow cycle");
    }
  }

  private static void awaitTerminal(
      WorkflowApplicationService service, Path workspace, String runId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!List.of("completed", "failed", "cancelled").contains(service.status(workspace, runId))
        && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
  }

  private static final class EchoTool implements OpenGisTool {
    private final ToolDefinition definition;

    private EchoTool(ObjectMapper mapper) {
      definition =
          new ToolDefinition(
              "echo",
              "Echo",
              "Echo input",
              "test",
              "core",
              "1",
              ToolRisk.READ,
              mapper.createObjectNode().put("type", "object"),
              List.of());
    }

    @Override
    public ToolDefinition definition() {
      return definition;
    }

    @Override
    public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
      return arguments;
    }
  }
}
