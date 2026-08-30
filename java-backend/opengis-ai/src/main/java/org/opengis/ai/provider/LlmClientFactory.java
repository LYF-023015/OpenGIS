package org.opengis.ai.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Selects one of the two retained wire protocols without leaking it into Agent. */
public final class LlmClientFactory {
  /** Exposes the Spring AI model for ChatClient based orchestration. */
  public ChatModel createChatModel(ProviderConfig config) {
    if (config.protocol() == ProviderProtocol.ANTHROPIC) {
      AnthropicChatOptions options =
          AnthropicChatOptions.builder()
              .baseUrl(config.baseUri().toString())
              .apiKey(config.apiKey())
              .model(config.model())
              .temperature(config.temperature())
              .maxTokens(config.maxTokens())
              .timeout(config.timeout())
              .build();
      return AnthropicChatModel.builder().options(options).build();
    }
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .baseUrl(config.baseUri().toString())
            .apiKey(config.apiKey())
            .model(config.model())
            .temperature(config.temperature())
            .maxTokens(config.maxTokens())
            .timeout(config.timeout())
            .streamUsage(true)
            .build();
    return OpenAiChatModel.builder().options(options).build();
  }
}
