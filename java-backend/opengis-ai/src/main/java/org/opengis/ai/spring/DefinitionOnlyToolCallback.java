package org.opengis.ai.spring;

import org.opengis.ai.model.LlmToolDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Projects an OpenGIS tool schema into Spring AI without granting it an execution path. */
public final class DefinitionOnlyToolCallback implements ToolCallback {
  private final ToolDefinition definition;

  public DefinitionOnlyToolCallback(LlmToolDefinition source) {
    definition =
        ToolDefinition.builder()
            .name(source.name())
            .description(source.description())
            .inputSchema(source.inputSchema().toString())
            .build();
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return definition;
  }

  @Override
  public String call(String arguments) {
    throw new IllegalStateException(
        "Tool execution is controlled by the OpenGIS agent runtime: " + definition.name());
  }
}
