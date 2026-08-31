/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.support;

import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;

/** Small adapter for defining an {@link OpenGisTool} from metadata and an executor function. */
public class FunctionalTool implements OpenGisTool {
  private final ToolDefinition definition;
  private final ToolExecutor executor;

  public FunctionalTool(ToolDefinition definition, ToolExecutor executor) {
    this.definition = definition;
    this.executor = executor;
  }

  @Override
  public ToolDefinition definition() {
    return definition;
  }

  @Override
  public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
    return executor.execute(arguments, context);
  }
}
