package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.opengis.agent.persistence.AgentProfileStore;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmRequest;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.provider.LlmClientFactory;
import org.opengis.ai.provider.ProviderCatalog;
import org.opengis.ai.provider.ProviderConfig;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.agent.LlmConfigurationState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Phase 5 LLM configuration, connection test, Agent loop, interrupt, and diagnostics RPC. */
@Component
public final class Phase5RpcMethods {
  private final RpcMethodRegistry registry;
  private final LlmConfigurationState configuration;
  private final LlmClientFactory clients;
  private final AgentApplicationService agents;
  private final CacheObservatory cache;

  public Phase5RpcMethods(
      RpcMethodRegistry registry,
      LlmConfigurationState configuration,
      LlmClientFactory clients,
      AgentApplicationService agents,
      CacheObservatory cache) {
    this.registry = registry;
    this.configuration = configuration;
    this.clients = clients;
    this.agents = agents;
    this.cache = cache;
  }

  @PostConstruct
  void registerMethods() {
    registry.registerOrReplace("rpc.agent.set_llm_config", configuration::configure);
    registry.registerOrReplace("rpc.agent.test_connection", this::testConnection);
    registry.registerOrReplace("rpc.agent.providers.list", this::providers);
    registry.registerOrReplace("rpc.agent.cache.stats", ignored -> cache.snapshot());
    registry.registerOrReplace("rpc.agent.get_status", ignored -> agents.status());
    registry.registerOrReplace("rpc.agent.profiles.install_defaults", this::installProfiles);
    registry.registerOrReplace("rpc.agent.interrupt", this::interrupt);
    registry.registerOrReplace("chat.user_message", this::chat);
  }

  private Object providers(JsonNode params) {
    return Map.of(
        "providers",
        ProviderCatalog.presets().stream()
            .map(
                preset ->
                    Map.of(
                        "id", preset.id(),
                        "label", preset.label(),
                        "protocol", preset.protocol().name().toLowerCase(java.util.Locale.ROOT),
                        "base_url", preset.baseUrl(),
                        "default_model", preset.defaultModel(),
                        "support_tier", preset.supportTier(),
                        "status", "migrated"))
            .toList());
  }

  private Object testConnection(JsonNode params) {
    ProviderConfig provider;
    try {
      provider = configuration.resolve(params);
    } catch (RuntimeException exception) {
      return Map.of("ok", false, "error", exception.getMessage());
    }
    if (provider.apiKey().isBlank() && !isLoopback(provider)) {
      return Map.of("ok", false, "error", "API key is required");
    }
    try {
      Duration timeout = Duration.ofMillis(Math.min(30_000, provider.timeout().toMillis()));
      LlmResponse response =
          clients
              .create(provider)
              .complete(
                  new LlmRequest(
                      provider.model(),
                      List.of(LlmMessage.user("Reply with OK")),
                      List.of(),
                      0.0,
                      8,
                      timeout,
                      Map.of()));
      return response.content().isBlank()
          ? Map.of("ok", false, "error", "Empty response from LLM")
          : Map.of("ok", true, "provider", provider.providerId(), "model", provider.model());
    } catch (RuntimeException exception) {
      return Map.of("ok", false, "error", configuration.sanitize(exception.getMessage(), provider));
    }
  }

  private Object chat(JsonNode params) {
    String message = text(params, "message", "");
    String conversationId = text(params, "conversation_id", "");
    if (message.isBlank() || conversationId.isBlank()) {
      throw invalidParams("message and conversation_id are required");
    }
    Path workspace = workspace(params);
    AgentApplicationService.ChatCommand command =
        new AgentApplicationService.ChatCommand(
            workspace,
            conversationId,
            text(params, "_connection_id", ""),
            message,
            text(params, "agent_profile", text(params, "profile_name", "gis-build")),
            text(params, "user_instructions", ""),
            Math.max(4096, params.path("context_window").asInt(128_000)),
            Duration.ofSeconds(Math.max(10, params.path("agent_timeout_seconds").asLong(600))),
            Duration.ofSeconds(Math.max(1, params.path("tool_timeout_seconds").asLong(120))));
    return agents.start(command);
  }

  private Object interrupt(JsonNode params) {
    String runId = text(params, "run_id", "");
    String workspace = text(params, "workspace_path", "");
    return agents.interrupt(
        workspace.isBlank() ? null : Path.of(workspace).toAbsolutePath().normalize(), runId);
  }

  private Object installProfiles(JsonNode params) {
    Path workspace = workspace(params);
    AgentProfileStore store = new AgentProfileStore(workspace);
    store.save(store.list());
    return Map.of(
        "status", "ok",
        "path", workspace.resolve(".opengis/agents.json").toString(),
        "profiles", store.list());
  }

  private static boolean isLoopback(ProviderConfig provider) {
    try {
      return InetAddress.getByName(provider.baseUri().getHost()).isLoopbackAddress();
    } catch (Exception exception) {
      return false;
    }
  }

  private static Path workspace(JsonNode params) {
    String value = text(params, "workspace_path", "");
    if (value.isBlank()) {
      throw invalidParams("workspace_path is required");
    }
    return Path.of(value).toAbsolutePath().normalize();
  }

  private static String text(JsonNode params, String field, String fallback) {
    return params.path(field).isTextual() ? params.path(field).asText() : fallback;
  }

  private static RpcException invalidParams(String message) {
    return new RpcException(org.opengis.common.protocol.JsonRpcErrorCodes.INVALID_PARAMS, message);
  }
}
