/** 文件职责：tool 后端领域：定义领域数据结构与协议。 */
package org.opengis.tool.context;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Ordered lifecycle fact emitted by ToolRuntime. */
public record ToolEvent(
    long sequence,
    Instant timestamp,
    String type,
    String runId,
    String toolCallId,
    String toolName,
    JsonNode payload) {}
