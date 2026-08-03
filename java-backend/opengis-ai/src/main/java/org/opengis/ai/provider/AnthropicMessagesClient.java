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

/** Streaming adapter for Anthropic Messages and compatible endpoints such as MiniMax. */
public final class AnthropicMessagesClient implements LlmClient {
  private final ProviderConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final ProviderProjector projector;

  public AnthropicMessagesClient(
      ProviderConfig config, HttpClient httpClient, ObjectMapper mapper) {
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
            .header("anthropic-version", "2023-06-01")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(projector.anthropic(routed)),
                    StandardCharsets.UTF_8));
    if (!config.apiKey().isBlank()) {
      builder.header("x-api-key", config.apiKey());
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
    if (path.endsWith("/v1/messages")) {
      return baseUri;
    }
    try {
      return new URI(
          baseUri.getScheme(),
          baseUri.getUserInfo(),
          baseUri.getHost(),
          baseUri.getPort(),
          path + "/v1/messages",
          baseUri.getQuery(),
          baseUri.getFragment());
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Invalid Anthropic-compatible base URI", exception);
    }
  }

  private void parseEvent(String data, LlmStreamAccumulator accumulator) {
    if (data.isBlank() || "[DONE]".equals(data)) {
      return;
    }
    try {
      JsonNode root = mapper.readTree(data);
      String type = root.path("type").asText();
      if ("error".equals(type)) {
        throw new LlmException(
            new LlmError(
                "provider_stream_error",
                root.path("error").path("message").asText("Provider stream failed"),
                false,
                200));
      }
      if ("message_start".equals(type)) {
        emitUsage(root.path("message").path("usage"), accumulator);
      } else if ("content_block_start".equals(type)) {
        JsonNode block = root.path("content_block");
        if ("tool_use".equals(block.path("type").asText())) {
          accumulator.accept(
              LlmChunk.tool(
                  root.path("index").asInt(),
                  block.path("id").asText(),
                  block.path("name").asText(),
                  ""));
        }
      } else if ("content_block_delta".equals(type)) {
        JsonNode delta = root.path("delta");
        if ("text_delta".equals(delta.path("type").asText())) {
          accumulator.accept(LlmChunk.text(delta.path("text").asText()));
        } else if ("input_json_delta".equals(delta.path("type").asText())) {
          accumulator.accept(
              LlmChunk.tool(
                  root.path("index").asInt(), "", "", delta.path("partial_json").asText()));
        }
      } else if ("message_delta".equals(type)) {
        emitUsage(root.path("usage"), accumulator);
        accumulator.accept(LlmChunk.done(root.path("delta").path("stop_reason").asText("stop")));
      }
    } catch (JacksonException exception) {
      throw new LlmException(
          new LlmError("invalid_provider_stream", "Invalid JSON in provider stream", false, 200),
          exception);
    }
  }

  private static void emitUsage(JsonNode usage, LlmStreamAccumulator accumulator) {
    if (!usage.isObject()) {
      return;
    }
    long input = usage.path("input_tokens").asLong();
    long read = usage.path("cache_read_input_tokens").asLong();
    long created = usage.path("cache_creation_input_tokens").asLong();
    accumulator.accept(
        LlmChunk.usage(
            new LlmUsage(input, usage.path("output_tokens").asLong(), read, read, created)));
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
