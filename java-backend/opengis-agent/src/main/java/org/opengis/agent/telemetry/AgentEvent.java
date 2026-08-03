package org.opengis.agent.telemetry;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
    long sequence,
    Instant timestamp,
    String type,
    String runId,
    String conversationId,
    Map<String, Object> data) {}
