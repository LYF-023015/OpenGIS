package org.opengis.tool.builtin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class WebFetchTool extends FunctionalTool {
  WebFetchTool(ObjectMapper mapper) {
    super(definition(mapper), WebFetchTool::fetch);
  }

  private static ToolDefinition definition(ObjectMapper mapper) {
    ObjectNode format = ToolSchemas.optionalString(mapper);
    format.putArray("enum").add("markdown").add("text").add("html");
    return new ToolDefinition(
        "webfetch",
        "Fetch Web Page",
        "Fetch an HTTP or HTTPS resource with bounded redirects and timeout.",
        "system",
        "core",
        "1.0.0",
        ToolRisk.NETWORK,
        ToolSchemas.object(
            mapper,
            Map.of(
                "url", ToolSchemas.string(mapper),
                "format", format,
                "timeout", ToolSchemas.integer(mapper, 1, 120)),
            "url"),
        List.of("network"));
  }

  private static JsonNode fetch(JsonNode args, ToolExecutionContext context) {
    URI uri;
    try {
      uri = URI.create(args.path("url").asText());
    } catch (IllegalArgumentException exception) {
      throw new ToolException("invalid_url", "Invalid URL", exception);
    }
    if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
      throw new ToolException("unsupported_url_scheme", "Only HTTP and HTTPS URLs are allowed");
    }
    Duration timeout = Duration.ofSeconds(args.path("timeout").asLong(30));
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", "OpenGIS-Java/0.1")
            .GET()
            .build();
    context.cancellation().throwIfCancelled();
    try {
      HttpResponse<java.io.InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      context.cancellation().throwIfCancelled();
      byte[] bytes;
      try (java.io.InputStream body = response.body()) {
        bytes = body.readNBytes(10_000_001);
      }
      if (bytes.length > 10_000_000) {
        throw new ToolException(
            "web_response_too_large", "Web response exceeds the 10 MB safety limit");
      }
      ObjectNode result = new ObjectMapper().createObjectNode();
      result.put("url", response.uri().toString());
      result.put("status", response.statusCode());
      result.put("content_type", response.headers().firstValue("content-type").orElse(""));
      result.put("content", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      return result;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ToolException("tool_cancelled", "Web fetch interrupted", exception);
    } catch (IOException exception) {
      throw new ToolException("webfetch_failed", "Web request failed", true, exception);
    }
  }
}
