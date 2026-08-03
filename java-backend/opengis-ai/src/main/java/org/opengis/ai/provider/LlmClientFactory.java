package org.opengis.ai.provider;

import java.net.http.HttpClient;
import org.opengis.ai.port.LlmClient;
import tools.jackson.databind.ObjectMapper;

/** Selects one of the two retained wire protocols without leaking it into Agent. */
public final class LlmClientFactory {
  private final HttpClient httpClient;
  private final ObjectMapper mapper;

  public LlmClientFactory(HttpClient httpClient, ObjectMapper mapper) {
    this.httpClient = httpClient;
    this.mapper = mapper;
  }

  public LlmClient create(ProviderConfig config) {
    return config.protocol() == ProviderProtocol.ANTHROPIC
        ? new AnthropicMessagesClient(config, httpClient, mapper)
        : new OpenAiCompatibleClient(config, httpClient, mapper);
  }
}
