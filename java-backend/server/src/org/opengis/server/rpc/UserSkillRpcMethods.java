/** 文件职责：server 后端领域：接收外部请求并调用应用服务。 */
package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Map;
import org.opengis.tool.skill.FileSystemSkillRepository;
import org.opengis.tool.skill.SkillDescriptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Discovers filesystem-backed instruction skills for the desktop catalog. */
@Component
public final class UserSkillRpcMethods {
  private final RpcMethodRegistry methods;
  private final FileSystemSkillRepository skills;

  public UserSkillRpcMethods(RpcMethodRegistry methods, FileSystemSkillRepository skills) {
    this.methods = methods;
    this.skills = skills;
  }

  @PostConstruct
  void registerMethods() {
    methods.registerOrReplace("rpc.user_skill.list", this::list);
  }

  private Object list(JsonNode params) {
    String workspaceValue = params.path("workspace_path").asString("");
    Path workspace =
        workspaceValue.isBlank() ? null : Path.of(workspaceValue).toAbsolutePath().normalize();
    return Map.of("skills", skills.discover(workspace).stream().map(this::project).toList());
  }

  private Map<String, Object> project(SkillDescriptor skill) {
    return Map.of(
        "name", skill.name(),
        "description", skill.description(),
        "location", skill.location().toString(),
        "directory", skill.location().getParent().toString(),
        "source", skill.source(),
        "tags", skill.tags(),
        "version", skill.version());
  }
}
