package org.opengis.ai.context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmToolDefinition;

/** Explicit builder which rejects accidental reordering of stable and dynamic sections. */
public final class CanonicalRequestBuilder {
  private final String model;
  private final List<PromptSection> sections = new ArrayList<>();
  private List<LlmToolDefinition> tools = List.of();
  private double temperature = 0.7;
  private int maxTokens = 4096;
  private Duration timeout = Duration.ofMinutes(5);
  private Map<String, Object> metadata = Map.of();

  public CanonicalRequestBuilder(String model) {
    this.model = model;
  }

  public CanonicalRequestBuilder add(
      String id,
      PromptSectionKind kind,
      List<LlmMessage> messages,
      PromptStability stability,
      PromptCachePolicy cachePolicy) {
    if (messages != null && !messages.isEmpty()) {
      sections.add(new PromptSection(id, kind, messages, stability, cachePolicy, Map.of()));
    }
    return this;
  }

  public CanonicalRequestBuilder tools(List<LlmToolDefinition> value) {
    tools = value;
    return this;
  }

  public CanonicalRequestBuilder options(
      double requestTemperature,
      int requestMaxTokens,
      Duration requestTimeout,
      Map<String, Object> requestMetadata) {
    temperature = requestTemperature;
    maxTokens = requestMaxTokens;
    timeout = requestTimeout;
    metadata = requestMetadata;
    return this;
  }

  public CanonicalRequest build() {
    return new CanonicalRequest(model, sections, tools, temperature, maxTokens, timeout, metadata);
  }
}
