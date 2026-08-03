package org.opengis.server.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.opengis.agent.context.AgentRunContext;
import org.opengis.agent.context.ContextManager;
import org.opengis.agent.execution.ToolSchemaProjector;
import org.opengis.agent.loop.AgentLoopRequest;
import org.opengis.agent.loop.AgentLoopResult;
import org.opengis.agent.loop.LoopKernel;
import org.opengis.agent.loop.RetryPolicy;
import org.opengis.agent.loop.TurnRunner;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.agent.persistence.SessionStore;
import org.opengis.agent.profile.AgentProfile;
import org.opengis.agent.profile.AgentProfiles;
import org.opengis.agent.profile.PermissionLevel;
import org.opengis.agent.session.SessionBusyException;
import org.opengis.agent.session.SessionCoordinator;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.context.RequestCompactor;
import org.opengis.ai.context.TokenEstimator;
import org.opengis.ai.provider.LlmClientFactory;
import org.opengis.ai.provider.ProviderConfig;
import org.opengis.knowledge.extraction.KnowledgeExtractor;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ToolRuntime;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Async application service so interrupt requests can arrive while a chat run is streaming. */
public final class AgentApplicationService {
  private final ExecutorService executor;
  private final SessionCoordinator sessions;
  private final LlmConfigurationState configuration;
  private final LlmClientFactory clients;
  private final ToolRegistry toolRegistry;
  private final ToolRuntime toolRuntime;
  private final CacheObservatory cache;
  private final UiRpcGateway ui;
  private final ObjectMapper mapper;

  public AgentApplicationService(
      ExecutorService executor,
      SessionCoordinator sessions,
      LlmConfigurationState configuration,
      LlmClientFactory clients,
      ToolRegistry toolRegistry,
      ToolRuntime toolRuntime,
      CacheObservatory cache,
      UiRpcGateway ui,
      ObjectMapper mapper) {
    this.executor = executor;
    this.sessions = sessions;
    this.configuration = configuration;
    this.clients = clients;
    this.toolRegistry = toolRegistry;
    this.toolRuntime = toolRuntime;
    this.cache = cache;
    this.ui = ui;
    this.mapper = mapper;
  }

  public Map<String, Object> start(ChatCommand command) {
    ProviderConfig provider = configuration.current();
    String runId = UUID.randomUUID().toString().replace("-", "");
    CancellationToken cancellation = new CancellationToken();
    SessionCoordinator.SessionLease lease;
    try {
      lease =
          sessions.acquire(
              command.conversationId(),
              runId,
              command.workspace(),
              command.connectionId(),
              cancellation);
    } catch (SessionBusyException exception) {
      return Map.of("status", "busy", "message", exception.getMessage());
    }
    RunArchive archive =
        RunArchive.open(command.workspace(), runId, command.message(), provider.model(), null);
    AgentNotificationBridge notifications =
        new AgentNotificationBridge(
            command.connectionId(), runId, command.conversationId(), ui, archive, mapper);
    persistSession(command, runId, "running", "");
    notifications.streamStart();
    var future =
        executor.submit(
            () -> {
              try (lease) {
                try {
                  execute(command, provider, runId, cancellation, archive, notifications);
                } catch (RuntimeException exception) {
                  if ("running".equals(archive.meta().path("status").asText())) {
                    String error =
                        exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
                    archive.close("error", "", error);
                    persistSession(command, runId, "error", error);
                    notifications.finish("error", "", error);
                  }
                }
              }
            });
    sessions.attachFuture(runId, future);
    return Map.of("status", "started", "run_id", runId, "model", provider.model());
  }

  public Map<String, Object> interrupt(Path workspace, String runId) {
    boolean cancelled =
        runId != null && !runId.isBlank()
            ? sessions.cancelRun(runId)
            : workspace == null ? false : sessions.cancelWorkspace(workspace) > 0;
    return Map.of("status", cancelled ? "cancelled" : "idle");
  }

  public Map<String, Object> status() {
    return Map.of(
        "status",
        sessions.activeRuns().isEmpty() ? "idle" : "running",
        "active_runs",
        sessions.activeRuns().values().stream()
            .map(
                run ->
                    Map.of(
                        "run_id", run.runId(),
                        "session_id", run.sessionId(),
                        "workspace_path", run.workspace().toString(),
                        "started_at", run.startedAt().toString()))
            .toList());
  }

  private void execute(
      ChatCommand command,
      ProviderConfig provider,
      String runId,
      CancellationToken cancellation,
      RunArchive archive,
      AgentNotificationBridge notifications) {
    AgentProfile profile = AgentProfiles.resolve(command.workspace(), command.profileName());
    AgentRunContext context =
        new AgentRunContext(
            command.workspace(),
            runId,
            command.conversationId(),
            command.connectionId(),
            profile,
            cancellation,
            notifications,
            toolEvents(archive),
            uiPort(command.connectionId()),
            permissionOverrides(profile));
    LoopKernel kernel =
        new LoopKernel(
            new ContextManager(command.workspace()),
            toolRegistry,
            new ToolSchemaProjector(),
            new RequestCompactor(new TokenEstimator(mapper)),
            cache,
            new TurnRunner(
                clients.create(provider),
                toolRuntime,
                mapper,
                new RetryPolicy(2, Duration.ofMillis(250))));
    AgentLoopRequest request =
        new AgentLoopRequest(
            command.message(),
            provider.providerId(),
            provider.model(),
            "You are OpenGIS, a careful GIS assistant. Use function calls for actions and report only completed work.",
            "Available Java tools: "
                + toolRegistry.definitions().stream().map(ToolDefinition::name).sorted().toList(),
            "Tool schemas are authoritative. Never invent tool results.",
            command.userInstructions(),
            provider.temperature(),
            provider.maxTokens(),
            command.contextWindow(),
            provider.timeout(),
            command.maxRuntime(),
            command.toolTimeout());
    AgentLoopResult result = kernel.run(request, context);
    String status = result.status();
    archive.appendLlmUsage((ObjectNode) mapper.valueToTree(result.usage()));
    archive.close(status, result.finalAnswer(), result.error());
    persistSession(command, runId, status, result.error());
    notifications.finish(status, result.finalAnswer(), result.error());
    if (result.completed()) {
      try {
        new KnowledgeExtractor().extract(command.workspace(), runId, result.finalAnswer());
      } catch (RuntimeException ignored) {
        // Knowledge extraction is deliberately non-fatal.
      }
    }
  }

  private void persistSession(ChatCommand command, String runId, String status, String error) {
    ObjectNode value = mapper.createObjectNode();
    value.put("id", command.conversationId());
    value.put("conversation_id", command.conversationId());
    value.put("run_id", runId);
    value.put("profile_name", command.profileName());
    value.put("status", status);
    value.put("updated_at", OffsetDateTime.now().toString());
    value.put("error", error == null ? "" : error);
    new SessionStore(command.workspace()).putSession(command.conversationId(), value);
  }

  private ToolEventSink toolEvents(RunArchive archive) {
    return event -> archive.appendToolCall((ObjectNode) mapper.valueToTree(event));
  }

  private UiRpcPort uiPort(String connectionId) {
    if (connectionId == null || connectionId.isBlank()) {
      return UiRpcPort.disconnected();
    }
    return new UiRpcPort() {
      @Override
      public java.util.concurrent.CompletionStage<tools.jackson.databind.JsonNode> request(
          String method, tools.jackson.databind.JsonNode params, Duration timeout) {
        return ui.request(connectionId, method, params);
      }

      @Override
      public void notify(String method, tools.jackson.databind.JsonNode params) {
        ui.notify(connectionId, method, mapper.convertValue(params, Map.class));
      }
    };
  }

  private Map<String, PermissionAction> permissionOverrides(AgentProfile profile) {
    if (profile.permissionLevel() != PermissionLevel.READ_ONLY) {
      return Map.of();
    }
    Map<String, PermissionAction> overrides = new HashMap<>();
    for (ToolDefinition definition : toolRegistry.definitions()) {
      if (definition.risk() == ToolRisk.WRITE || definition.risk() == ToolRisk.DESTRUCTIVE) {
        overrides.put(definition.name(), PermissionAction.DENY);
      }
    }
    return Map.copyOf(overrides);
  }

  public record ChatCommand(
      Path workspace,
      String conversationId,
      String connectionId,
      String message,
      String profileName,
      String userInstructions,
      int contextWindow,
      Duration maxRuntime,
      Duration toolTimeout) {}
}
