/** 文件职责：agent 后端领域：定义领域数据结构与协议。 */
package org.opengis.assistant.agent.telemetry;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
    long sequence,
    Instant timestamp,
    String type,
    String runId,
    String conversationId,
    Map<String, Object> data) {}
