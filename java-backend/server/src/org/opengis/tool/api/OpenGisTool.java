/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.api;

import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;

/** Business-facing tool SPI. Implementations do not know about RPC or Agent profiles. */
public interface OpenGisTool {
  ToolDefinition definition();

  JsonNode execute(JsonNode arguments, ToolExecutionContext context) throws ToolException;
}
