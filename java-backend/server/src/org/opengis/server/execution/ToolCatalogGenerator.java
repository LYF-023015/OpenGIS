/** 文件职责：server 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.server.execution;

import java.nio.file.Path;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.raster.RasterService;
import org.opengis.server.config.ToolConfiguration;
import org.opengis.server.config.ToolConfiguration.ToolPluginAssembly;
import org.opengis.tool.registry.ToolCatalogWriter;
import tools.jackson.databind.ObjectMapper;

/** Command-line entry point for regenerating the effective {@code tool-catalog.json}. */
public final class ToolCatalogGenerator {
  private ToolCatalogGenerator() {}

  public static void main(String[] args) {
    Path workspace = args.length == 0 ? Path.of(".") : Path.of(args[0]);
    ObjectMapper mapper = new ObjectMapper();
    ScriptExecutionBridge bridge = new ScriptExecutionBridge(mapper);
    ExecutionServices services = new ExecutionServices(mapper, bridge);
    try (ToolPluginAssembly assembly =
        ToolConfiguration.createAssembly(
            mapper, new RasterService(new CrsService()), services, bridge)) {
      Path output = new ToolCatalogWriter(assembly.registry(), mapper).write(workspace);
      System.out.println("Wrote " + assembly.registry().size() + " tools to " + output);
    } finally {
      services.close();
    }
  }
}
