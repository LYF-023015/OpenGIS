/** 文件职责：plugins 后端领域：声明可插拔能力及其注册方式。 */
package org.opengis.tool.plugins.gis;

import java.util.Set;
import org.opengis.core.plugin.PluginDescriptor;
import org.opengis.gis.raster.RasterService;
import org.opengis.tool.registry.ToolCatalogPlugin;
import tools.jackson.databind.ObjectMapper;

/** Contributes GIS domain services to the Agent-facing tool registry. */
public final class GisToolsPlugin extends ToolCatalogPlugin {
  public GisToolsPlugin(ObjectMapper mapper, RasterService rasters) {
    super(
        new PluginDescriptor("gis-tools", "1.0.0", Set.of("core-tools")),
        () -> GisToolCatalog.create(mapper, rasters));
  }
}
