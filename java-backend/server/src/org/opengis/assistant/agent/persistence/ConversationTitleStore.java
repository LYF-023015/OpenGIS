/** 文件职责：agent 后端领域：管理状态或持久化数据。 */
package org.opengis.assistant.agent.persistence;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ArrayNode;

/** Set of conversations that already received an automatically generated title. */
public class ConversationTitleStore {
  private final JsonFileStore files = new JsonFileStore();
  private final Path path;

  public ConversationTitleStore(Path workspaceRoot) {
    path = new WorkspaceLayout(workspaceRoot).resolve("titled_conversations.json");
  }

  public Set<String> load() {
    Set<String> conversationIds = new LinkedHashSet<>();
    var root = files.read(path);
    if (root.isArray()) {
      root.valueStream().map(node -> node.asString()).forEach(conversationIds::add);
    }
    return conversationIds;
  }

  public void save(Set<String> conversationIds) {
    ArrayNode root = files.objectMapper().createArrayNode();
    conversationIds.forEach(root::add);
    files.write(path, root);
  }
}
