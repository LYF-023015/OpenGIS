package org.opengis.tool.builtin;

import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;

class FunctionalTool implements OpenGisTool {
  private final ToolDefinition definition;
  private final ToolExecutor executor;

  FunctionalTool(ToolDefinition definition, ToolExecutor executor) {
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
