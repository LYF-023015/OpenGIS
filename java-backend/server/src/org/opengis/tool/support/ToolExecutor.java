/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.support;

import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;

@FunctionalInterface
public interface ToolExecutor {
  JsonNode execute(JsonNode arguments, ToolExecutionContext context);
}
