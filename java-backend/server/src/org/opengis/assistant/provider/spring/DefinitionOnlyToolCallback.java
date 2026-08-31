/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.provider.spring;

import org.opengis.assistant.model.LlmToolDefinition;
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
