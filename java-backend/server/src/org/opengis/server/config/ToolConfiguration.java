/** 文件职责：server 后端领域：集中声明运行配置。 */
package org.opengis.server.config;

import java.util.List;
import org.opengis.core.plugin.OpenGisPlugin;
import org.opengis.core.plugin.PluginContext;
import org.opengis.core.plugin.PluginRuntime;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.raster.RasterService;
import org.opengis.tool.plugins.gis.GisToolsPlugin;
import org.opengis.tool.plugins.memory.MemoryToolsPlugin;
import org.opengis.server.execution.ExecutionServices;
import org.opengis.server.execution.ExecutionToolsPlugin;
import org.opengis.server.execution.ScriptExecutionBridge;
import org.opengis.tool.builtin.CoreToolsPlugin;
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
  ToolRegistry toolRegistry() {
    return new ToolRegistry();
  }

  @Bean(destroyMethod = "close")
  PluginRuntime pluginRuntime(
      ToolRegistry registry,
      ObjectMapper objectMapper,
      RasterService rasterService,
      ScriptExecutionBridge executionBridge,
      ExecutionServices executionServices,
      FileSystemSkillRepository skills) {
    List<OpenGisPlugin> plugins =
        toolPlugins(objectMapper, rasterService, executionServices, executionBridge, skills);
    return new PluginRuntime(
            PluginContext.builder().service(ToolRegistry.class, registry).build(), plugins)
        .start();
  }

  public static ToolPluginAssembly createAssembly(
      ObjectMapper objectMapper,
      RasterService rasterService,
      ExecutionServices executionServices,
      ScriptExecutionBridge executionBridge) {
    return createAssembly(
        objectMapper,
        rasterService,
        executionServices,
        executionBridge,
        new FileSystemSkillRepository());
  }

  public static ToolPluginAssembly createAssembly(
      ObjectMapper objectMapper,
      RasterService rasterService,
      ExecutionServices executionServices,
      ScriptExecutionBridge executionBridge,
      FileSystemSkillRepository skills) {
    ToolRegistry registry = new ToolRegistry();
    PluginRuntime runtime =
        new PluginRuntime(
                PluginContext.builder().service(ToolRegistry.class, registry).build(),
                toolPlugins(
                    objectMapper, rasterService, executionServices, executionBridge, skills))
            .start();
    return new ToolPluginAssembly(registry, runtime);
  }

  private static List<OpenGisPlugin> toolPlugins(
      ObjectMapper objectMapper,
      RasterService rasterService,
      ExecutionServices executionServices,
      ScriptExecutionBridge executionBridge,
      FileSystemSkillRepository skills) {
    return List.of(
        new CoreToolsPlugin(objectMapper, skills),
        new MemoryToolsPlugin(objectMapper),
        new GisToolsPlugin(objectMapper, rasterService),
        new ExecutionToolsPlugin(objectMapper, executionServices, executionBridge));
  }

  @Bean
  ToolRuntime toolRuntime(
      ToolRegistry registry,
      PluginRuntime pluginRuntime,
      ObjectMapper objectMapper,
      ScriptExecutionBridge executionBridge) {
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

  /** Explicit owner for non-Spring callers that need the effective tool catalog. */
  public record ToolPluginAssembly(ToolRegistry registry, PluginRuntime runtime)
      implements AutoCloseable {
    @Override
    public void close() {
      runtime.close();
    }
  }
}
