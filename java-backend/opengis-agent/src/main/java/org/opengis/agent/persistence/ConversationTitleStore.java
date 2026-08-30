package org.opengis.agent.persistence;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
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
