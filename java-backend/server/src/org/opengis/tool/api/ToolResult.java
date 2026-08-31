/** 文件职责：tool 后端领域：定义领域数据结构与协议。 */
package org.opengis.tool.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Normalized result shape shared by RPC, Agent and RunArchive. */
public record ToolResult(
    boolean success,
    ToolStatus status,
    String title,
    JsonNode output,
    Map<String, Object> metadata,
    List<ArtifactRef> artifacts,
    boolean truncated,
    ToolError error) {
  public ToolResult {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
  }

  public static ToolResult completed(String title, JsonNode output) {
    return new ToolResult(
        true, ToolStatus.COMPLETED, title, output, Map.of(), List.of(), false, null);
  }

  public static ToolResult failure(
      ToolStatus status, String code, String message, JsonNode details) {
    return new ToolResult(
        false,
        status,
        message,
        null,
        Map.of(),
        List.of(),
        false,
        new ToolError(code, message, details, false));
  }

  /** Structured failure safe to archive and return to an LLM or Renderer. */
  public record ToolError(String code, String message, JsonNode details, boolean retryable) {}
}
