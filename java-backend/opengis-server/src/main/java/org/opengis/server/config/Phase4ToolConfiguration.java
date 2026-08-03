package org.opengis.server.config;

import org.opengis.gis.crs.CrsService;
import org.opengis.gis.raster.RasterService;
import org.opengis.gis.tool.GisToolCatalog;
import org.opengis.server.phase8.Phase8ExecutionBridge;
import org.opengis.server.phase8.Phase8Services;
import org.opengis.server.phase8.Phase8ToolCatalog;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.permission.WorkspacePermissionRuleSource;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Server is the composition root; opengis-tool itself stays framework-neutral. */
@Configuration
public class Phase4ToolConfiguration {
  @Bean
  RasterService rasterService() {
    return new RasterService(new CrsService());
  }

  @Bean
  ToolRegistry toolRegistry(
      ObjectMapper objectMapper,
      RasterService rasterService,
      Phase8ExecutionBridge phase8Bridge,
      Phase8Services phase8Services) {
    return BuiltinToolCatalog.registry(objectMapper)
        .registerAll(GisToolCatalog.create(objectMapper, rasterService))
        .registerAll(Phase8ToolCatalog.create(objectMapper, phase8Services, phase8Bridge));
  }

  @Bean
  ToolRuntime toolRuntime(
      ToolRegistry registry, ObjectMapper objectMapper, Phase8ExecutionBridge phase8Bridge) {
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(new WorkspacePermissionRuleSource()),
            new ArtifactMaterializer(),
            objectMapper);
    phase8Bridge.bind(runtime);
    return runtime;
  }
}
