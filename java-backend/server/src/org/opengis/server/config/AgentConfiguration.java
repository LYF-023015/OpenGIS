/** 文件职责：server 后端领域：集中声明运行配置。 */
package org.opengis.server.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opengis.assistant.agent.session.SessionCoordinator;
import org.opengis.assistant.model.context.CacheObservatory;
import org.opengis.assistant.provider.LlmClientFactory;
import org.opengis.assistant.provider.LlmModelFactory;
import org.opengis.server.agent.AgentApplicationService;
import org.opengis.server.agent.LlmConfigurationState;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.tool.skill.FileSystemSkillRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Phase 5 composition root; domain modules remain independent of Spring and WebSocket. */
@Configuration
public class AgentConfiguration {
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
  LlmModelFactory llmClientFactory() {
    return new LlmClientFactory();
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
      LlmModelFactory llmClientFactory,
      ToolRegistry toolRegistry,
      ToolRuntime toolRuntime,
      CacheObservatory cacheObservatory,
      UiRpcGateway uiRpcGateway,
      FileSystemSkillRepository skills,
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
        skills,
        objectMapper);
  }
}
