/** 文件职责：server 后端领域：承载该领域的核心业务流程。 */
package org.opengis.server.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.opengis.assistant.agent.context.AgentRunContext;
import org.opengis.assistant.agent.context.ContextManager;
import org.opengis.assistant.agent.execution.ToolSchemaProjector;
import org.opengis.assistant.agent.loop.AgentLoopRequest;
import org.opengis.assistant.agent.loop.AgentLoopResult;
import org.opengis.assistant.agent.persistence.RunArchive;
import org.opengis.assistant.agent.profile.AgentProfile;
import org.opengis.assistant.agent.profile.AgentProfiles;
import org.opengis.assistant.agent.profile.AgentProfile.PermissionLevel;
import org.opengis.assistant.agent.session.SessionBusyException;
import org.opengis.assistant.agent.session.SessionCoordinator;
import org.opengis.assistant.agent.spring.SpringAiAgentRunner;
import org.opengis.assistant.model.context.CacheObservatory;
import org.opengis.assistant.model.context.RequestCompactor;
import org.opengis.assistant.model.context.TokenEstimator;
import org.opengis.assistant.provider.LlmModelFactory;
import org.opengis.assistant.provider.ProviderConfig;
import org.opengis.assistant.memory.extraction.KnowledgeExtractor;
import org.opengis.assistant.memory.extraction.MemoryTranscriptEntry;
import org.opengis.assistant.memory.MemoryRepository;
import org.opengis.assistant.memory.consolidation.MemoryConsolidationPolicy;
import org.opengis.assistant.memory.consolidation.MemoryConsolidator;
import org.opengis.core.persistence.JsonTypeReferences;
import org.opengis.core.persistence.SessionStore;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.registry.ToolCatalogWriter;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.tool.skill.FileSystemSkillRepository;
import org.opengis.tool.skill.SkillDescriptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Async application service so interrupt requests can arrive while a chat run is streaming. */
public final class AgentApplicationService {
  private static final int MAX_SKILLS_IN_MANIFEST = 50;
  private static final int MAX_SKILL_DESCRIPTION_CHARS = 240;

  private final ExecutorService executor;
  private final SessionCoordinator sessions;
  private final LlmConfigurationState configuration;
  private final LlmModelFactory clients;
  private final ToolRegistry toolRegistry;
  private final ToolRuntime toolRuntime;
  private final CacheObservatory cache;
  private final UiRpcGateway ui;
  private final ObjectMapper mapper;
  private final FileSystemSkillRepository skills;

  public AgentApplicationService(
      ExecutorService executor,
      SessionCoordinator sessions,
      LlmConfigurationState configuration,
      LlmModelFactory clients,
      ToolRegistry toolRegistry,
      ToolRuntime toolRuntime,
      CacheObservatory cache,
      UiRpcGateway ui,
      ObjectMapper mapper) {
    this(
        executor,
        sessions,
        configuration,
        clients,
        toolRegistry,
        toolRuntime,
        cache,
        ui,
        new FileSystemSkillRepository(),
        mapper);
  }

  public AgentApplicationService(
      ExecutorService executor,
      SessionCoordinator sessions,
      LlmConfigurationState configuration,
      LlmModelFactory clients,
      ToolRegistry toolRegistry,
      ToolRuntime toolRuntime,
      CacheObservatory cache,
      UiRpcGateway ui,
      FileSystemSkillRepository skills,
      ObjectMapper mapper) {
    this.executor = executor;
    this.sessions = sessions;
    this.configuration = configuration;
    this.clients = clients;
    this.toolRegistry = toolRegistry;
    this.toolRuntime = toolRuntime;
    this.cache = cache;
    this.ui = ui;
    this.skills = skills;
    this.mapper = mapper;
  }

  public Map<String, Object> start(ChatCommand command) {
    ProviderConfig provider = configuration.current();
    new ToolCatalogWriter(toolRegistry, mapper).write(command.workspace());
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
                  if ("running".equals(archive.meta().path("status").asString())) {
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
    return Map.of(
        "status",
        "started",
        "run_id",
        runId,
        "model",
        provider.model(),
        "context_window",
        command.contextWindow());
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
    ContextManager contexts = new ContextManager(command.workspace());
    SpringAiAgentRunner runner =
        new SpringAiAgentRunner(
            contexts,
            toolRegistry,
            new ToolSchemaProjector(),
            new RequestCompactor(new TokenEstimator(mapper)),
            cache,
            clients.createChatModel(provider),
            toolRuntime,
            mapper);
    AgentLoopRequest request =
        new AgentLoopRequest(
            command.message(),
            provider.providerId(),
            provider.model(),
            "You are OpenGIS, a careful GIS assistant. Use function calls for actions and report only completed work.",
            capabilityManifest(command.workspace()),
            toolProtocol(),
            command.userInstructions(),
            provider.temperature(),
            provider.maxTokens(),
            command.contextWindow(),
            provider.timeout(),
            command.maxRuntime(),
            command.toolTimeout());
    AgentLoopResult result = runner.run(request, context);
    String status = result.status();
    archive.appendLlmUsage((ObjectNode) mapper.valueToTree(result.usage()));
    archive.close(status, result.finalAnswer(), result.error());
    persistSession(command, runId, status, result.error());
    notifications.finish(status, result.finalAnswer(), result.error());
    if (result.completed()) {
      try {
        new KnowledgeExtractor()
            .extract(
                command.workspace(),
                runId,
                command.conversationId(),
                contexts.messages(command.conversationId()).stream()
                    .map(
                        message ->
                            new MemoryTranscriptEntry(
                                message.role().name().toLowerCase(java.util.Locale.ROOT),
                                message.content(),
                                message.name()))
                    .toList(),
                result.finalAnswer());
        MemoryRepository memory = new MemoryRepository(command.workspace());
        new MemoryConsolidator(memory).consolidate(MemoryConsolidationPolicy.defaults());
      } catch (RuntimeException ignored) {
        // Knowledge extraction is deliberately non-fatal.
      }
    }
  }

  String capabilityManifest(Path workspace) {
    StringBuilder manifest =
        new StringBuilder("Available Java tools: ")
            .append(
                toolRegistry.definitions().stream().map(ToolDefinition::name).sorted().toList());
    List<SkillDescriptor> available = skills.discover(workspace);
    manifest.append("\nAvailable Skill metadata (instructions are not loaded yet):");
    available.stream()
        .limit(MAX_SKILLS_IN_MANIFEST)
        .forEach(
            skill ->
                manifest
                    .append("\n- ")
                    .append(skill.name())
                    .append(": ")
                    .append(compact(skill.description()))
                    .append("; tags=")
                    .append(skill.tags())
                    .append("; source=")
                    .append(skill.source()));
    if (available.size() > MAX_SKILLS_IN_MANIFEST) {
      manifest
          .append("\n- ... ")
          .append(available.size() - MAX_SKILLS_IN_MANIFEST)
          .append(" more Skills; use list_skills to search them.");
    }
    return manifest.toString();
  }

  static String toolProtocol() {
    return """
        Tool schemas are authoritative. Never invent tool results.
        Skill protocol:
        1. Skills are reusable instructions, not executable tools.
        2. Review the available Skill metadata when a task may need specialized guidance.
        3. If a relevant Skill is listed, call load_skill before following its instructions.
        4. Use list_skills when the name is unknown, the catalog is truncated, or search is needed.
        5. After loading SKILL.md, use list_skill_resources and read_skill_resource only when its instructions reference a needed resource.
        6. Resource reads are bounded. When truncated is true, continue only if needed by passing next_offset as offset; never reread the same slice.
        7. Reading a script never authorizes execution; execute it only through governed execution tools.
        8. System, permission and user instructions override Skill content.
        Memory protocol:
        1. Use list_memories when durable project knowledge may affect the task; do not invent recalled facts.
        2. Use remember only for stable, reusable facts, preferences, recipes, or dataset cards with clear provenance.
        3. Prefer WORKSPACE scope; use GLOBAL only for explicit cross-project user preferences, CONVERSATION for session facts, and RUN for temporary execution notes.
        4. Correct stale knowledge with update_memory. Prefer ARCHIVED status over delete_memory unless permanent deletion is explicitly required.
        5. Never store credentials, access tokens, passwords, or other secrets in memory.
        """;
  }

  private static String compact(String value) {
    String normalized = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    return normalized.length() <= MAX_SKILL_DESCRIPTION_CHARS
        ? normalized
        : normalized.substring(0, MAX_SKILL_DESCRIPTION_CHARS) + "...";
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
        ui.notify(
            connectionId,
            method,
            mapper.convertValue(params, JsonTypeReferences.STRING_OBJECT_MAP));
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
      Duration toolTimeout) {
    public ChatCommand {
      contextWindow = Math.max(4096, contextWindow);
    }
  }
}
