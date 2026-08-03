package org.opengis.agent.persistence;

import java.nio.file.Path;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Compatible reader/writer for sessions and inbox entries in sessions.json. */
public class SessionStore {
  private final JsonFileStore files;
  private final Path path;

  public SessionStore(Path workspaceRoot) {
    this(workspaceRoot, new JsonFileStore());
  }

  public SessionStore(Path workspaceRoot, JsonFileStore files) {
    this.files = files;
    this.path = new WorkspaceLayout(workspaceRoot).resolve("sessions.json");
  }

  public synchronized ObjectNode load() {
    ObjectNode root = files.readObject(path);
    ensureObject(root, "sessions");
    ensureObject(root, "inbox");
    return root;
  }

  public synchronized void save(ObjectNode state) {
    ensureObject(state, "sessions");
    ensureObject(state, "inbox");
    files.write(path, state);
  }

  public synchronized void putSession(String id, JsonNode session) {
    ObjectNode root = load();
    ((ObjectNode) root.get("sessions")).set(id, session);
    save(root);
  }

  public synchronized void putInboxItem(String id, JsonNode item) {
    ObjectNode root = load();
    ((ObjectNode) root.get("inbox")).set(id, item);
    save(root);
  }

  private static void ensureObject(ObjectNode root, String field) {
    if (!root.path(field).isObject()) {
      root.set(field, root.objectNode());
    }
  }
}
