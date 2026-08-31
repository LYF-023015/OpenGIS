/** 文件职责：tool 后端领域：验证对应功能的行为与边界。 */
package org.opengis.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkspacePermissionRuleSourceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @TempDir Path workspace;

  @Test
  void scopesAllowRuleByArgumentPattern() throws Exception {
    writeRules(
        """
        {"rules":[{
          "id":"workspace-data-only",
          "tool":"write_file",
          "action":"allow",
          "argument_patterns":{"file_path":"data/**"}
        }]}
        """);
    WorkspacePermissionRuleSource rules = new WorkspacePermissionRuleSource();

    ObjectNode allowed = mapper.createObjectNode().put("file_path", "data/roads.geojson");
    ObjectNode outside = mapper.createObjectNode().put("file_path", "reports/roads.geojson");
    assertThat(rules.match(workspace, "write_file", "gis-build", allowed)).isPresent();
    assertThat(rules.match(workspace, "write_file", "gis-build", outside)).isEmpty();
  }

  @Test
  void scopesNetworkRuleByParsedHost() throws Exception {
    writeRules(
        """
        {"rules":[{
          "id":"trusted-api-only",
          "tool":"http_request",
          "action":"allow",
          "host_patterns":["api.example.com","*.trusted.test"]
        }]}
        """);
    WorkspacePermissionRuleSource rules = new WorkspacePermissionRuleSource();

    ObjectNode trusted = mapper.createObjectNode().put("url", "https://api.example.com/v1/layers");
    ObjectNode untrusted = mapper.createObjectNode().put("url", "https://evil.example/v1/layers");
    assertThat(rules.match(workspace, "http_request", "gis-build", trusted)).isPresent();
    assertThat(rules.match(workspace, "http_request", "gis-build", untrusted)).isEmpty();
  }

  private void writeRules(String json) throws Exception {
    Path path = workspace.resolve(".opengis/permissions.json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, json);
  }
}
