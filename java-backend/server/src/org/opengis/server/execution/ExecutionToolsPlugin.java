/** 文件职责：server 后端领域：声明可插拔能力及其注册方式。 */
package org.opengis.server.execution;

import java.util.Set;
import org.opengis.core.plugin.PluginDescriptor;
import org.opengis.tool.registry.ToolCatalogPlugin;
import tools.jackson.databind.ObjectMapper;

/** Contributes server-owned code, Operation, Workflow and Worker adapters. */
public final class ExecutionToolsPlugin extends ToolCatalogPlugin {
  public ExecutionToolsPlugin(
      ObjectMapper mapper, ExecutionServices services, ScriptExecutionBridge bridge) {
    super(
        new PluginDescriptor("execution-tools", "1.0.0", Set.of("core-tools")),
        () -> ExecutionToolCatalog.create(mapper, services, bridge));
  }
}
