/** 文件职责：plugins 后端领域：声明可插拔能力及其注册方式。 */
package org.opengis.tool.plugins.memory;

import java.util.Set;
import org.opengis.core.plugin.PluginDescriptor;
import org.opengis.tool.registry.ToolCatalogPlugin;
import tools.jackson.databind.ObjectMapper;

/** Built-in adapter exposing durable memory as optional Agent tools. */
public final class MemoryToolsPlugin extends ToolCatalogPlugin {
  public MemoryToolsPlugin(ObjectMapper mapper) {
    super(
        new PluginDescriptor("memory-tools", "1.0.0", Set.of("core-tools")),
        () -> MemoryTools.create(mapper));
  }
}
