package org.opengis.tool.builtin;

import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;

@FunctionalInterface
interface ToolExecutor {
  JsonNode execute(JsonNode arguments, ToolExecutionContext context);
}
