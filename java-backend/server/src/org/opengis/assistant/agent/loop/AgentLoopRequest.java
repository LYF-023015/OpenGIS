/** 文件职责：agent 后端领域：定义领域数据结构与协议。 */
package org.opengis.assistant.agent.loop;

import java.time.Duration;

public record AgentLoopRequest(
    String prompt,
    String providerId,
    String model,
    String systemPrompt,
    String capabilityManifest,
    String toolProtocol,
    String userPreferences,
    double temperature,
    int maxTokens,
    int contextWindow,
    Duration providerTimeout,
    Duration maxRuntime,
    Duration toolTimeout) {
  public AgentLoopRequest {
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt is required");
    }
    providerId = providerId == null ? "custom" : providerId;
    systemPrompt = systemPrompt == null ? "You are OpenGIS, a GIS assistant." : systemPrompt;
    capabilityManifest = capabilityManifest == null ? "" : capabilityManifest;
    toolProtocol = toolProtocol == null ? "" : toolProtocol;
    userPreferences = userPreferences == null ? "" : userPreferences;
    contextWindow = contextWindow <= 0 ? 128_000 : contextWindow;
    providerTimeout = providerTimeout == null ? Duration.ofMinutes(5) : providerTimeout;
    maxRuntime = maxRuntime == null ? Duration.ofMinutes(10) : maxRuntime;
    toolTimeout = toolTimeout == null ? Duration.ofMinutes(10) : toolTimeout;
  }
}
