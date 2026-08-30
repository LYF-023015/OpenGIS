package org.opengis.server.config;

import org.opengis.gis.crs.CrsService;
import org.opengis.gis.raster.RasterService;
import org.opengis.gis.tool.GisToolCatalog;
import org.opengis.server.execution.ExecutionServices;
import org.opengis.server.execution.ExecutionToolCatalog;
import org.opengis.server.execution.ScriptExecutionBridge;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.permission.WorkspacePermissionRuleSource;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import org.opengis.tool.skill.FileSystemSkillRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Server is the composition root; opengis-tool itself stays framework-neutral. */
@Configuration
public class ToolConfiguration {
  @Bean
  FileSystemSkillRepository fileSystemSkillRepository(OpenGisSkillProperties properties) {
    return new FileSystemSkillRepository(properties.toSettings());
  }

  @Bean
  RasterService rasterService() {
    return new RasterService(new CrsService());
  }

  @Bean
  ToolRegistry toolRegistry(
      ObjectMapper objectMapper,
      RasterService rasterService,
      ScriptExecutionBridge executionBridge,
      ExecutionServices executionServices,
      FileSystemSkillRepository skills) {
    return createRegistry(objectMapper, rasterService, executionServices, executionBridge, skills);
  }

  public static ToolRegistry createRegistry(
      ObjectMapper objectMapper,
      RasterService rasterService,
      ExecutionServices executionServices,
      ScriptExecutionBridge executionBridge) {
    return createRegistry(
        objectMapper,
        rasterService,
        executionServices,
        executionBridge,
        new FileSystemSkillRepository());
  }

  public static ToolRegistry createRegistry(
      ObjectMapper objectMapper,
      RasterService rasterService,
      ExecutionServices executionServices,
      ScriptExecutionBridge executionBridge,
      FileSystemSkillRepository skills) {
    return BuiltinToolCatalog.registry(objectMapper, skills)
        .registerAll(GisToolCatalog.create(objectMapper, rasterService))
        .registerAll(ExecutionToolCatalog.create(objectMapper, executionServices, executionBridge));
  }

  @Bean
  ToolRuntime toolRuntime(
      ToolRegistry registry, ObjectMapper objectMapper, ScriptExecutionBridge executionBridge) {
    ToolRuntime runtime =
        new ToolRuntime(
            registry,
            new JsonSchemaValidator(),
            new PermissionRuntime(new WorkspacePermissionRuleSource()),
            new ArtifactMaterializer(),
            objectMapper);
    executionBridge.bind(runtime);
    return runtime;
  }
}
