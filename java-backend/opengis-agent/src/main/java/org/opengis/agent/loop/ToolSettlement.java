package org.opengis.agent.loop;

import org.opengis.ai.model.LlmToolCall;
import org.opengis.tool.api.ToolResult;

public record ToolSettlement(LlmToolCall call, ToolResult result, long durationMillis) {}
