/** 文件职责：tool 后端领域：声明可插拔能力及其注册方式。 */
package org.opengis.tool.registry;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.opengis.core.plugin.OpenGisPlugin;
import org.opengis.core.plugin.PluginContext;
import org.opengis.core.plugin.PluginDescriptor;
import org.opengis.core.plugin.PluginHandle;
import org.opengis.tool.api.OpenGisTool;

/** Adapts an existing tool catalog to the common plugin lifecycle. */
public class ToolCatalogPlugin implements OpenGisPlugin {
  private final PluginDescriptor descriptor;
  private final Supplier<List<OpenGisTool>> tools;

  public ToolCatalogPlugin(PluginDescriptor descriptor, Supplier<List<OpenGisTool>> tools) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.tools = Objects.requireNonNull(tools, "tools");
  }

  @Override
  public PluginDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public PluginHandle mount(PluginContext context) {
    return context.require(ToolRegistry.class).contributeAll(tools.get());
  }
}
