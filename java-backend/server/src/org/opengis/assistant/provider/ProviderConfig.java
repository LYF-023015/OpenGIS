/** 文件职责：ai 后端领域：集中声明运行配置。 */
package org.opengis.assistant.provider;

import java.net.URI;
import java.time.Duration;

/** In-memory connection configuration. API keys must never be persisted or logged. */
public record ProviderConfig(
    String providerId,
    ProviderProtocol protocol,
    String model,
    String apiKey,
    URI baseUri,
    double temperature,
    int maxTokens,
    Duration timeout) {
  public ProviderConfig {
    providerId = providerId == null || providerId.isBlank() ? "custom" : providerId;
    protocol = protocol == null ? ProviderProtocol.OPENAI : protocol;
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model is required");
    }
    apiKey = apiKey == null ? "" : apiKey;
    if (baseUri == null) {
      throw new IllegalArgumentException("baseUri is required");
    }
    temperature = Math.max(0.0, Math.min(2.0, temperature));
    maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
    timeout =
        timeout == null || timeout.isZero() || timeout.isNegative()
            ? Duration.ofMinutes(5)
            : timeout;
  }
}
