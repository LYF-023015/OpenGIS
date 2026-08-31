/** 文件职责：tool 后端领域：声明可插拔能力及其注册方式。 */
package org.opengis.tool.builtin;

import java.util.Set;
import org.opengis.core.plugin.PluginDescriptor;
import org.opengis.tool.registry.ToolCatalogPlugin;
import org.opengis.tool.skill.FileSystemSkillRepository;
import tools.jackson.databind.ObjectMapper;

/** Contributes the framework-neutral core Tool catalog. */
public final class CoreToolsPlugin extends ToolCatalogPlugin {
  public CoreToolsPlugin(ObjectMapper mapper, FileSystemSkillRepository skills) {
    super(
        new PluginDescriptor("core-tools", "1.0.0", Set.of()),
        () -> BuiltinToolCatalog.create(mapper, skills));
  }
}
