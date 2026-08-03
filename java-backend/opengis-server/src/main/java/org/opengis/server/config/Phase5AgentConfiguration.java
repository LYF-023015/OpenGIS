package org.opengis.server.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opengis.agent.session.SessionCoordinator;
import org.opengis.ai.context.CacheObservatory;
import org.opengis.ai.provider.LlmClientFactory;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.agent.LlmConfigurationState;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Phase 5 composition root; domain modules remain independent of Spring and WebSocket. */
@Configuration
public class Phase5AgentConfiguration {
  @Bean(destroyMethod = "close")
  ExecutorService agentExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  SessionCoordinator sessionCoordinator() {
    return new SessionCoordinator();
  }

  @Bean
  LlmConfigurationState llmConfigurationState() {
    return new LlmConfigurationState();
  }

  @Bean
  HttpClient providerHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Bean
  LlmClientFactory llmClientFactory(HttpClient providerHttpClient, ObjectMapper objectMapper) {
    return new LlmClientFactory(providerHttpClient, objectMapper);
  }

  @Bean
  CacheObservatory cacheObservatory(ObjectMapper objectMapper) {
    return new CacheObservatory(objectMapper);
  }

  @Bean
  AgentApplicationService agentApplicationService(
      ExecutorService agentExecutor,
      SessionCoordinator sessionCoordinator,
      LlmConfigurationState llmConfigurationState,
      LlmClientFactory llmClientFactory,
      ToolRegistry toolRegistry,
      ToolRuntime toolRuntime,
      CacheObservatory cacheObservatory,
      UiRpcGateway uiRpcGateway,
      ObjectMapper objectMapper) {
    return new AgentApplicationService(
        agentExecutor,
        sessionCoordinator,
        llmConfigurationState,
        llmClientFactory,
        toolRegistry,
        toolRuntime,
        cacheObservatory,
        uiRpcGateway,
        objectMapper);
  }
}
