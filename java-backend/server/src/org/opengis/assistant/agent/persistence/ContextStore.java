/** 文件职责：agent 后端领域：管理状态或持久化数据。 */
package org.opengis.assistant.agent.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.WorkspaceLayout;
import tools.jackson.databind.node.ObjectNode;

/** Conversation context snapshots stored under .opengis/contexts. */
public class ContextStore {
  private final JsonFileStore files = new JsonFileStore();
  private final WorkspaceLayout layout;

  public ContextStore(Path workspaceRoot) {
    layout = new WorkspaceLayout(workspaceRoot);
  }

  public Optional<ObjectNode> load(String conversationId) {
    Path path = path(conversationId);
    return Files.exists(path) ? Optional.of(files.readObject(path)) : Optional.empty();
  }

  public void save(String conversationId, ObjectNode context) {
    files.write(path(conversationId), context);
  }

  private Path path(String conversationId) {
    if (conversationId == null || !conversationId.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe conversation id: " + conversationId);
    }
    return layout.resolve("contexts/" + conversationId + ".json");
  }
}
