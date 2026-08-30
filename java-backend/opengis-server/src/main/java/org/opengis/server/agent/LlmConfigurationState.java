package org.opengis.server.agent;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.opengis.ai.provider.ProviderCatalog;
import org.opengis.ai.provider.ProviderConfig;
import org.opengis.ai.provider.ProviderPreset;
import org.opengis.ai.provider.ProviderProtocol;
import tools.jackson.databind.JsonNode;

/** Process-memory LLM configuration; credentials are never written to workspace files. */
public final class LlmConfigurationState {
  private final AtomicReference<ProviderConfig> current =
      new AtomicReference<>(
          new ProviderConfig(
              "openai",
              ProviderProtocol.OPENAI,
              "gpt-4o",
              "",
              URI.create("https://api.openai.com/v1"),
              0.7,
              4096,
              Duration.ofMinutes(5)));

  public ProviderConfig current() {
    return current.get();
  }

  public ProviderConfig resolve(JsonNode params) {
    ProviderConfig previous = current.get();
    String protocolText = text(params, "protocol", previous.protocol().name());
    ProviderProtocol protocol = ProviderProtocol.parse(protocolText);
    boolean routeSpecified =
        params.path("provider").isString() || params.path("base_url").isString();
    String baseUrl = text(params, "base_url", routeSpecified ? "" : previous.baseUri().toString());
    String providerId =
        text(
            params,
            "provider",
            routeSpecified ? identify(baseUrl, protocol) : previous.providerId());
    ProviderPreset preset = ProviderCatalog.find(providerId).orElse(null);
    if (baseUrl.isBlank()) {
      baseUrl = preset == null ? previous.baseUri().toString() : preset.baseUrl();
    }
    if (baseUrl.isBlank()) {
      throw new IllegalArgumentException("base_url is required for provider " + providerId);
    }
    String model =
        text(
            params,
            "model",
            preset == null || preset.defaultModel().isBlank()
                ? previous.model()
                : preset.defaultModel());
    String apiKey =
        params.path("api_key").isString() ? params.path("api_key").asString() : previous.apiKey();
    double temperature =
        params.path("temperature").isNumber()
            ? params.path("temperature").asDouble()
            : previous.temperature();
    int maxTokens =
        params.path("max_tokens").isIntegralNumber()
            ? params.path("max_tokens").asInt()
            : previous.maxTokens();
    long timeoutMillis =
        params.path("timeout_ms").isIntegralNumber()
            ? params.path("timeout_ms").asLong()
            : previous.timeout().toMillis();
    return new ProviderConfig(
        providerId,
        protocol,
        model,
        apiKey,
        URI.create(baseUrl),
        temperature,
        maxTokens,
        Duration.ofMillis(Math.max(100, timeoutMillis)));
  }

  public Map<String, Object> configure(JsonNode params) {
    ProviderConfig resolved = resolve(params);
    current.set(resolved);
    return safeView(resolved);
  }

  public Map<String, Object> safeView(ProviderConfig config) {
    return Map.of(
        "status", "configured",
        "provider", config.providerId(),
        "protocol", config.protocol().name().toLowerCase(java.util.Locale.ROOT),
        "model", config.model(),
        "base_url", config.baseUri().toString(),
        "has_api_key", !config.apiKey().isBlank(),
        "route", config.providerId() + "/" + config.model());
  }

  public String sanitize(String message, ProviderConfig config) {
    String safe = message == null ? "LLM provider request failed" : message;
    return config.apiKey().isBlank() ? safe : safe.replace(config.apiKey(), "***");
  }

  private static String identify(String baseUrl, ProviderProtocol protocol) {
    if (!baseUrl.isBlank()) {
      String normalized = baseUrl.replaceAll("/+$", "");
      for (ProviderPreset preset : ProviderCatalog.presets()) {
        if (!preset.baseUrl().isBlank()
            && preset.baseUrl().replaceAll("/+$", "").equalsIgnoreCase(normalized)) {
          return preset.id();
        }
      }
    }
    return protocol == ProviderProtocol.ANTHROPIC ? "anthropic" : "openai";
  }

  private static String text(JsonNode params, String field, String fallback) {
    return params.path(field).isString() && !params.path(field).asString().isBlank()
        ? params.path(field).asString().strip()
        : fallback;
  }
}
