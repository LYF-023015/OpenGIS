/** 文件职责：framework 后端领域：声明可插拔能力及其注册方式。 */
package org.opengis.core.plugin;

/** One composable OpenGIS capability with explicit dependencies and reversible lifecycle. */
public interface OpenGisPlugin {
  PluginDescriptor descriptor();

  PluginHandle mount(PluginContext context);
}
