package org.opengis.ai.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.opengis.ai.model.LlmChunk;
import org.opengis.ai.model.LlmError;
import org.opengis.ai.model.LlmRequest;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmUsage;
import org.opengis.ai.port.LlmClient;
import org.opengis.ai.port.LlmException;
import org.opengis.ai.stream.LlmStreamAccumulator;
import org.opengis.framework.concurrent.CancellationSignal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Streaming adapter for OpenAI Chat Completions and compatible endpoints. */
public final class OpenAiCompatibleClient implements LlmClient {
  private final ProviderConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final ProviderProjector projector;

  public OpenAiCompatibleClient(ProviderConfig config, HttpClient httpClient, ObjectMapper mapper) {
    this.config = config;
    this.httpClient = httpClient;
    this.mapper = mapper;
    this.projector = new ProviderProjector(mapper);
  }

  @Override
  public LlmResponse complete(
      LlmRequest request, Consumer<LlmChunk> onChunk, CancellationSignal cancellation) {
    LlmRequest routed = route(request);
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, onChunk);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(endpoint(config.baseUri()))
            .timeout(routed.timeout())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(projector.openAi(routed)), StandardCharsets.UTF_8));
    if (!config.apiKey().isBlank()) {
      if ("azure".equalsIgnoreCase(config.providerId())) {
        builder.header("api-key", config.apiKey());
      } else {
        builder.header("Authorization", "Bearer " + config.apiKey());
      }
    }
    SseHttpSupport.exchange(
        httpClient,
        builder.build(),
        routed.timeout(),
        cancellation,
        data -> parseEvent(data, accumulator));
    return accumulator.finish();
  }

  static URI endpoint(URI baseUri) {
    String path = (baseUri.getPath() == null ? "" : baseUri.getPath()).replaceAll("/+$", "");
    if (path.endsWith("/chat/completions")) {
      return baseUri;
    }
    try {
      return new URI(
          baseUri.getScheme(),
          baseUri.getUserInfo(),
          baseUri.getHost(),
          baseUri.getPort(),
          path + "/chat/completions",
          baseUri.getQuery(),
          baseUri.getFragment());
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Invalid OpenAI-compatible base URI", exception);
    }
  }

  private void parseEvent(String data, LlmStreamAccumulator accumulator) {
    if (data.isBlank()) {
      return;
    }
    if ("[DONE]".equals(data)) {
      return;
    }
    try {
      JsonNode root = mapper.readTree(data);
      if (root.has("error")) {
        throw new LlmException(
            new LlmError(
                "provider_stream_error",
                root.path("error").path("message").asText("Provider stream failed"),
                false,
                200));
      }
      if (root.path("usage").isObject()) {
        accumulator.accept(LlmChunk.usage(openAiUsage(root.path("usage"))));
      }
      for (JsonNode choice : root.path("choices")) {
        JsonNode delta = choice.path("delta");
        if (delta.path("content").isTextual()) {
          accumulator.accept(LlmChunk.text(delta.path("content").asText()));
        }
        for (JsonNode call : delta.path("tool_calls")) {
          JsonNode function = call.path("function");
          accumulator.accept(
              LlmChunk.tool(
                  call.path("index").asInt(0),
                  call.path("id").asText(),
                  function.path("name").asText(),
                  function.path("arguments").asText()));
        }
        if (choice.path("finish_reason").isTextual()) {
          accumulator.accept(LlmChunk.done(choice.path("finish_reason").asText()));
        }
      }
    } catch (JacksonException exception) {
      throw new LlmException(
          new LlmError("invalid_provider_stream", "Invalid JSON in provider stream", false, 200),
          exception);
    }
  }

  private static LlmUsage openAiUsage(JsonNode usage) {
    long prompt = usage.path("prompt_tokens").asLong();
    long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
    cached = Math.max(cached, usage.path("prompt_cache_hit_tokens").asLong());
    return new LlmUsage(
        prompt,
        usage.path("completion_tokens").asLong(),
        cached,
        cached,
        usage.path("prompt_cache_miss_tokens").asLong());
  }

  private LlmRequest route(LlmRequest request) {
    return new LlmRequest(
        config.model(),
        request.messages(),
        request.tools(),
        config.temperature(),
        config.maxTokens(),
        request.timeout() == null ? config.timeout() : request.timeout(),
        request.metadata());
  }
}
