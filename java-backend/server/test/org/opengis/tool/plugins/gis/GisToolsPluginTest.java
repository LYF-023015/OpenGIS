/** 文件职责：plugins 后端领域：验证对应功能的行为与边界。 */
package org.opengis.tool.plugins.gis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opengis.core.plugin.PluginContext;
import org.opengis.core.plugin.PluginRuntime;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.raster.RasterService;
import org.opengis.tool.builtin.CoreToolsPlugin;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.skill.FileSystemSkillRepository;
import tools.jackson.databind.ObjectMapper;

class GisToolsPluginTest {
  @Test
  void contributesGisToolsAndRemovesThemOnClose() {
    ObjectMapper mapper = new ObjectMapper();
    ToolRegistry registry = new ToolRegistry();
    PluginRuntime runtime =
        new PluginRuntime(
                PluginContext.builder().service(ToolRegistry.class, registry).build(),
                List.of(
                    new CoreToolsPlugin(mapper, new FileSystemSkillRepository()),
                    new GisToolsPlugin(mapper, new RasterService(new CrsService()))))
            .start();

    assertThat(registry.find("gis_capabilities")).isPresent();
    assertThat(registry.find("osm_call")).isPresent();

    runtime.close();

    assertThat(registry.size()).isZero();
  }
}
