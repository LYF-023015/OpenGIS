package org.opengis.server.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.phase8.Phase8ExecutionBridge;
import org.opengis.server.phase8.Phase8Services;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolResult;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.workflow.execution.WorkflowEngine;
import org.opengis.workflow.execution.WorkflowEventSink;
import org.opengis.workflow.execution.WorkflowNodeRunner;
import org.opengis.workflow.execution.WorkflowRunSnapshot;
import org.opengis.workflow.execution.WorkflowRunStore;
import org.opengis.workflow.model.WorkflowDocument;
import org.opengis.workflow.persistence.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Phase 6 application boundary for asynchronous DAG execution and MessagePart projection. */
public final class WorkflowApplicationService {
  private static final int MAX_SUBWORKFLOW_DEPTH = 8;
  private final ExecutorService executor;
  private final AgentApplicationService agents;
  private final ToolRuntime tools;
  private final UiRpcGateway ui;
  private final ObjectMapper mapper;
  private final Phase8Services phase8;
  private final Phase8ExecutionBridge phase8Bridge;
  private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();

  public WorkflowApplicationService(
      ExecutorService executor,
      AgentApplicationService agents,
      ToolRuntime tools,
      UiRpcGateway ui,
      ObjectMapper mapper) {
    this(executor, agents, tools, ui, mapper, null, null);
  }

  public WorkflowApplicationService(
      ExecutorService executor,
      AgentApplicationService agents,
      ToolRuntime tools,
      UiRpcGateway ui,
      ObjectMapper mapper,
      Phase8Services phase8,
      Phase8ExecutionBridge phase8Bridge) {
    this.executor = executor;
    this.agents = agents;
    this.tools = tools;
    this.ui = ui;
    this.mapper = mapper;
    this.phase8 = phase8;
    this.phase8Bridge = phase8Bridge;
  }

  public Map<String, Object> start(
      Path workspace,
      WorkflowDocument workflow,
      String conversationId,
      String connectionId,
      boolean resume,
      String requestedRunId) {
    String runId =
        requestedRunId == null || requestedRunId.isBlank()
            ? UUID.randomUUID().toString().replace("-", "")
            : requestedRunId;
    if (active.containsKey(runId)) return Map.of("status", "busy", "run_id", runId);
    AtomicBoolean cancelled = new AtomicBoolean();
    RunArchive archive =
        RunArchive.open(workspace, runId, "Workflow: " + workflow.name(), "workflow-v2", null);
    WorkflowProjection projection =
        new WorkflowProjection(connectionId, conversationId, runId, workflow, archive);
    var childRuns = ConcurrentHashMap.<String>newKeySet();
    FutureTask<Void> future =
        new FutureTask<>(
            () -> {
              try {
                WorkflowRunSnapshot result =
                    new WorkflowEngine(mapper)
                        .execute(
                            workspace,
                            workflow,
                            runId,
                            request ->
                                runNode(
                                    request, conversationId, connectionId, List.of(workflow.id())),
                            cancelled,
                            projection,
                            resume);
                archive.close(
                    result.status(),
                    "Workflow " + workflow.name() + " " + result.status(),
                    result.error());
              } catch (RuntimeException exception) {
                String error =
                    exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                archive.close("failed", "", error);
              } finally {
                active.remove(runId);
              }
              return null;
            });
    active.put(
        runId, new ActiveRun(workspace.toAbsolutePath().normalize(), cancelled, future, childRuns));
    executor.execute(future);
    return Map.of("status", "started", "run_id", runId, "workflow_id", workflow.id());
  }

  public boolean cancel(Path workspace, String runId) {
    ActiveRun run = active.get(runId);
    if (run == null || !run.workspace().equals(workspace.toAbsolutePath().normalize()))
      return false;
    run.cancelled().set(true);
    run.childRuns().forEach(childRunId -> agents.interrupt(workspace, childRunId));
    return run.future().cancel(true);
  }

  public String status(Path workspace, String runId) {
    return new WorkflowRunStore(workspace)
        .load(runId)
        .map(WorkflowRunSnapshot::status)
        .orElse("unknown");
  }

  private WorkflowNodeRunner.NodeResult runNode(
      WorkflowNodeRunner.NodeRequest request,
      String conversationId,
      String connectionId,
      List<String> workflowStack) {
    if (request.cancellation().isCancelled())
      return WorkflowNodeRunner.NodeResult.failed("cancelled", "");
    return switch (request.node().type()) {
      case "agent_task" -> runAgentNode(request, conversationId, connectionId);
      case "tool_call" -> runToolNode(request, conversationId, connectionId);
      case "operation" -> runOperationNode(request, conversationId, connectionId);
      case "java_script" -> runJavaScriptNode(request, conversationId, connectionId);
      case "subworkflow" ->
          runSubworkflowNode(request, conversationId, connectionId, workflowStack);
      default -> WorkflowNodeRunner.NodeResult.failed("Unsupported node type", "");
    };
  }

  private WorkflowNodeRunner.NodeResult runSubworkflowNode(
      WorkflowNodeRunner.NodeRequest request,
      String conversationId,
      String connectionId,
      List<String> workflowStack) {
    String workflowId = request.node().execution().ref();
    if (workflowStack.contains(workflowId)) {
      return WorkflowNodeRunner.NodeResult.failed(
          "Recursive subworkflow cycle: "
              + String.join(" -> ", workflowStack)
              + " -> "
              + workflowId,
          "");
    }
    if (workflowStack.size() >= MAX_SUBWORKFLOW_DEPTH) {
      return WorkflowNodeRunner.NodeResult.failed(
          "Subworkflow depth exceeds " + MAX_SUBWORKFLOW_DEPTH, "");
    }
    WorkflowDocument child =
        new WorkflowStore(request.workspace()).loadDocument(workflowId).orElse(null);
    if (child == null) {
      return WorkflowNodeRunner.NodeResult.failed("Subworkflow was not found: " + workflowId, "");
    }

    String childRunId =
        request.workflowRunId() + "-" + request.node().id() + "-" + request.attempt() + "-sub";
    AtomicBoolean childCancelled = new AtomicBoolean(request.cancellation().isCancelled());
    Thread watcher = watchCancellation(request, childCancelled);
    try {
      List<String> childStack = new ArrayList<>(workflowStack);
      childStack.add(workflowId);
      WorkflowRunSnapshot result =
          new WorkflowEngine(mapper)
              .execute(
                  request.workspace(),
                  child,
                  childRunId,
                  nested -> runNode(nested, conversationId, connectionId, List.copyOf(childStack)),
                  childCancelled,
                  WorkflowEventSink.noop(),
                  false);
      ObjectNode output = mapper.valueToTree(result);
      if ("completed".equals(result.status())) {
        return WorkflowNodeRunner.NodeResult.completed(output, childRunId, true);
      }
      return new WorkflowNodeRunner.NodeResult("failed", output, childRunId, result.error(), true);
    } catch (RuntimeException exception) {
      return new WorkflowNodeRunner.NodeResult(
          "failed", null, childRunId, exception.getMessage(), true);
    } finally {
      watcher.interrupt();
    }
  }

  private WorkflowNodeRunner.NodeResult runOperationNode(
      WorkflowNodeRunner.NodeRequest request, String conversationId, String connectionId) {
    if (phase8 == null || phase8Bridge == null)
      return WorkflowNodeRunner.NodeResult.failed("Phase 8 Operation executor is unavailable", "");
    ToolExecutionContext context = phase8Context(request, conversationId, connectionId);
    Thread cancellationWatcher = watchCancellation(request, context.cancellation());
    try {
      ObjectNode parameters = mapper.createObjectNode();
      request.node().params().forEach(parameters::set);
      ObjectNode result =
          phase8
              .operations()
              .run(
                  request.workspace(),
                  request.node().execution().ref(),
                  parameters,
                  Duration.ofMinutes(10),
                  true,
                  context.cancellation(),
                  phase8Bridge.callbacks(context));
      return "success".equals(result.path("status").asText())
          ? WorkflowNodeRunner.NodeResult.completed(result, result.path("run_id").asText(), true)
          : WorkflowNodeRunner.NodeResult.failed(result.path("status").asText("failed"), "");
    } catch (RuntimeException exception) {
      return WorkflowNodeRunner.NodeResult.failed(exception.getMessage(), "");
    } finally {
      cancellationWatcher.interrupt();
    }
  }

  private WorkflowNodeRunner.NodeResult runJavaScriptNode(
      WorkflowNodeRunner.NodeRequest request, String conversationId, String connectionId) {
    if (phase8 == null)
      return WorkflowNodeRunner.NodeResult.failed(
          "Phase 8 Java Script executor is unavailable", "");
    ToolExecutionContext context = phase8Context(request, conversationId, connectionId);
    Thread cancellationWatcher = watchCancellation(request, context.cancellation());
    try {
      Path sourcePath = request.workspace().resolve(request.node().execution().ref()).normalize();
      if (!sourcePath.startsWith(request.workspace().toAbsolutePath().normalize())
          || !sourcePath.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".java")
          || !Files.isRegularFile(sourcePath)) {
        return WorkflowNodeRunner.NodeResult.failed("Java workflow source was not found", "");
      }
      ObjectNode arguments = mapper.createObjectNode();
      arguments.put("code", Files.readString(sourcePath));
      arguments.put("name", request.node().title());
      arguments.put(
          "run_id", request.workflowRunId() + "-" + request.node().id() + "-" + request.attempt());
      ObjectNode parameters = arguments.putObject("params");
      request.node().params().forEach(parameters::set);
      ObjectNode result = phase8.runScript(context, arguments);
      return result.path("ok").asBoolean()
          ? WorkflowNodeRunner.NodeResult.completed(result, result.path("runId").asText(), false)
          : WorkflowNodeRunner.NodeResult.failed(result.path("error").asText("failed"), "");
    } catch (Exception exception) {
      return WorkflowNodeRunner.NodeResult.failed(exception.getMessage(), "");
    } finally {
      cancellationWatcher.interrupt();
    }
  }

  private static Thread watchCancellation(
      WorkflowNodeRunner.NodeRequest request, CancellationToken token) {
    return Thread.ofVirtual()
        .name("opengis-workflow-cancel-" + request.node().id())
        .start(
            () -> {
              try {
                while (!token.isCancelled() && !request.cancellation().isCancelled()) {
                  Thread.sleep(25);
                }
                if (request.cancellation().isCancelled()) token.cancel();
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            });
  }

  private static Thread watchCancellation(
      WorkflowNodeRunner.NodeRequest request, AtomicBoolean cancelled) {
    return Thread.ofVirtual()
        .name("opengis-subworkflow-cancel-" + request.node().id())
        .start(
            () -> {
              try {
                while (!cancelled.get() && !request.cancellation().isCancelled()) {
                  Thread.sleep(25);
                }
                if (request.cancellation().isCancelled()) cancelled.set(true);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            });
  }

  private ToolExecutionContext phase8Context(
      WorkflowNodeRunner.NodeRequest request, String conversationId, String connectionId) {
    CancellationToken token = new CancellationToken();
    if (request.cancellation().isCancelled()) token.cancel();
    return new ToolExecutionContext(
        request.workspace(),
        request.workflowRunId() + "-" + request.node().id(),
        conversationId,
        "gis-build",
        Map.of(),
        PermissionAction.ALLOW,
        token,
        ToolEventSink.noop(),
        uiPort(connectionId));
  }

  private WorkflowNodeRunner.NodeResult runAgentNode(
      WorkflowNodeRunner.NodeRequest request, String conversationId, String connectionId) {
    String prompt = buildPrompt(request);
    AgentApplicationService.ChatCommand command =
        new AgentApplicationService.ChatCommand(
            request.workspace(),
            request.childSessionId(),
            connectionId,
            prompt,
            request.node().execution().ref(),
            "",
            128_000,
            Duration.ofMinutes(10),
            Duration.ofMinutes(2));
    Map<String, Object> started = agents.start(command);
    if (!"started".equals(started.get("status"))) {
      return WorkflowNodeRunner.NodeResult.failed(
          String.valueOf(started.getOrDefault("message", "child agent busy")), "");
    }
    String childRunId = String.valueOf(started.get("run_id"));
    ActiveRun parent = active.get(request.workflowRunId());
    if (parent != null) parent.childRuns().add(childRunId);
    while (!request.cancellation().isCancelled()) {
      var archive = RunArchive.load(request.workspace(), childRunId);
      if (archive.isPresent()) {
        String status = archive.get().meta().path("status").asText();
        if ("completed".equals(status) || "success".equals(status)) {
          ObjectNode output = mapper.createObjectNode();
          output.put("run_id", childRunId);
          output.put("session_id", request.childSessionId());
          output.put("answer", archive.get().finalAnswer());
          return WorkflowNodeRunner.NodeResult.completed(output, childRunId, false);
        }
        if ("error".equals(status) || "cancelled".equals(status)) {
          return WorkflowNodeRunner.NodeResult.failed(
              archive.get().meta().path("error").asText(status), childRunId);
        }
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return WorkflowNodeRunner.NodeResult.failed("cancelled", childRunId);
      }
    }
    agents.interrupt(request.workspace(), childRunId);
    return WorkflowNodeRunner.NodeResult.failed("cancelled", childRunId);
  }

  private WorkflowNodeRunner.NodeResult runToolNode(
      WorkflowNodeRunner.NodeRequest request, String conversationId, String connectionId) {
    CancellationToken token = new CancellationToken();
    if (request.cancellation().isCancelled()) token.cancel();
    ToolExecutionContext context =
        new ToolExecutionContext(
            request.workspace(),
            request.workflowRunId(),
            conversationId,
            "gis-build",
            Map.of(),
            PermissionAction.ALLOW,
            token,
            ToolEventSink.noop(),
            uiPort(connectionId));
    ObjectNode arguments = mapper.createObjectNode();
    request.node().params().forEach(arguments::set);
    ToolResult result =
        tools.execute(
            new ToolCall(
                request.workflowRunId() + "-" + request.node().id() + "-" + request.attempt(),
                request.node().execution().ref(),
                arguments),
            context);
    if (result.success()) {
      return WorkflowNodeRunner.NodeResult.completed(result.output(), "", true);
    }
    return WorkflowNodeRunner.NodeResult.failed(
        result.error() == null ? result.status().name() : result.error().message(), "");
  }

  private String buildPrompt(WorkflowNodeRunner.NodeRequest request) {
    List<String> parts = new ArrayList<>();
    parts.add("Workflow child session " + request.childSessionId());
    parts.add("Task: " + request.node().title());
    if (request.node().description() != null && !request.node().description().isBlank())
      parts.add(request.node().description());
    if (request.node().inputContract() != null && !request.node().inputContract().isBlank())
      parts.add("Input contract: " + request.node().inputContract());
    if (request.node().outputContract() != null && !request.node().outputContract().isBlank())
      parts.add("Output contract: " + request.node().outputContract());
    if (!request.predecessorOutputs().isEmpty())
      parts.add("Predecessor outputs: " + mapper.writeValueAsString(request.predecessorOutputs()));
    parts.add("Complete this node using registered Java tools. Return concrete handoff values.");
    return String.join("\n", parts);
  }

  private org.opengis.tool.api.UiRpcPort uiPort(String connectionId) {
    if (connectionId == null || connectionId.isBlank())
      return org.opengis.tool.api.UiRpcPort.disconnected();
    return new org.opengis.tool.api.UiRpcPort() {
      @Override
      public java.util.concurrent.CompletionStage<JsonNode> request(
          String method, JsonNode params, Duration timeout) {
        return ui.request(connectionId, method, params);
      }

      @Override
      public void notify(String method, JsonNode params) {
        ui.notify(connectionId, method, mapper.convertValue(params, Map.class));
      }
    };
  }

  private record ActiveRun(
      Path workspace, AtomicBoolean cancelled, Future<?> future, java.util.Set<String> childRuns) {}

  private final class WorkflowProjection implements WorkflowEventSink {
    private final String connectionId;
    private final String conversationId;
    private final String runId;
    private final WorkflowDocument workflow;
    private final RunArchive archive;
    private final Map<String, String> statuses = new LinkedHashMap<>();

    private WorkflowProjection(
        String connectionId,
        String conversationId,
        String runId,
        WorkflowDocument workflow,
        RunArchive archive) {
      this.connectionId = connectionId;
      this.conversationId = conversationId;
      this.runId = runId;
      this.workflow = workflow;
      this.archive = archive;
      workflow.nodes().forEach(node -> statuses.put(node.id(), "pending"));
    }

    @Override
    public synchronized void emit(String event, Map<String, Object> data) {
      ObjectNode archivedEvent =
          mapper.valueToTree(Map.of("type", event, "run_id", runId, "data", data));
      archive.appendEvent(archivedEvent);
      String nodeId = String.valueOf(data.getOrDefault("node_id", ""));
      if (!nodeId.isBlank()) {
        if (event.endsWith("node_started")) statuses.put(nodeId, "in_progress");
        else if (event.endsWith("node_completed") || event.endsWith("node_skipped"))
          statuses.put(nodeId, "completed");
        else if (event.endsWith("failed")) statuses.put(nodeId, "failed");
      }
      String partStatus =
          "workflow.completed".equals(event)
              ? "completed"
              : "workflow.cancelled".equals(event)
                  ? "cancelled"
                  : "workflow.failed".equals(event) ? "failed" : "streaming";
      List<Map<String, Object>> steps =
          workflow.nodes().stream()
              .map(
                  node ->
                      Map.<String, Object>of(
                          "id",
                          node.id(),
                          "title",
                          node.title(),
                          "status",
                          statuses.get(node.id()),
                          "note",
                          node.description() == null ? "" : node.description()))
              .toList();
      Map<String, Object> planData =
          Map.of(
              "title",
              workflow.name(),
              "steps",
              steps,
              "workflow",
              true,
              "runId",
              runId,
              "workflowId",
              workflow.id());
      Map<String, Object> part = new LinkedHashMap<>();
      part.put("id", "workflow-plan-" + runId);
      part.put("type", "plan");
      part.put("status", partStatus);
      part.put("text", "");
      part.put("run_id", runId);
      part.put("runId", runId);
      part.put("created_at", System.currentTimeMillis());
      part.put(
          "data", Map.of("planData", planData, "event", event, "conversation_id", conversationId));
      archive.appendMessagePart((ObjectNode) mapper.valueToTree(part));
      if (connectionId != null && !connectionId.isBlank()) {
        try {
          ui.notify(connectionId, "chat.message_part", Map.of("part", part));
        } catch (RuntimeException ignored) {
        }
      }
    }
  }
}
