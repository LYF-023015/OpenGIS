/** 文件职责：agent 后端领域：管理状态或持久化数据。 */
package org.opengis.assistant.agent.persistence;

import java.nio.file.Path;
import java.util.List;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Compatible reader/writer for persisted permission rules. */
public class PermissionRuleStore {
  private final JsonFileStore files = new JsonFileStore();
  private final Path path;

  public PermissionRuleStore(Path workspaceRoot) {
    path = new WorkspaceLayout(workspaceRoot).resolve("permissions.json");
  }

  public List<ObjectNode> list() {
    ObjectNode root = files.readObject(path);
    if (!root.path("rules").isArray()) {
      return List.of();
    }
    return root.path("rules").valueStream().map(node -> (ObjectNode) node).toList();
  }

  public void save(List<ObjectNode> rules) {
    ObjectNode root = files.objectMapper().createObjectNode();
    ArrayNode values = root.putArray("rules");
    rules.forEach(values::add);
    files.write(path, root);
  }

  public synchronized ObjectNode add(ObjectNode rule) {
    List<ObjectNode> rules = new java.util.ArrayList<>(list());
    rules.add(rule);
    save(rules);
    return rule;
  }

  public synchronized boolean remove(String ruleId) {
    List<ObjectNode> rules = new java.util.ArrayList<>(list());
    boolean removed = rules.removeIf(rule -> ruleId.equals(rule.path("id").asString()));
    if (removed) {
      save(rules);
    }
    return removed;
  }
}
