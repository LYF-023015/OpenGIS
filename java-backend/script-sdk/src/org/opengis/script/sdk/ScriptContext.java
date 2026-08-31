/** 文件职责：script 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.script.sdk;

import java.nio.file.Path;

/** Immutable script context. Direct server objects are deliberately not exposed. */
public final class ScriptContext {
  private final Path workspace;
  private final String runId;
  private final ToolClient tools;
  private final ArtifactClient artifacts;
  private final MapClient map;
  private final ProgressEmitter progress;
  private final ProtocolTransport transport;

  public ScriptContext(Path workspace, String runId, ProtocolTransport transport) {
    this.workspace = workspace.toAbsolutePath().normalize();
    this.runId = runId;
    this.transport = transport;
    this.tools = new ToolClient(transport);
    this.artifacts = new ArtifactClient(transport);
    this.map = new MapClient(transport);
    this.progress = new ProgressEmitter(transport);
  }

  public Path workspace() {
    return workspace;
  }

  public String runId() {
    return runId;
  }

  public ToolClient tools() {
    return tools;
  }

  public ArtifactClient artifacts() {
    return artifacts;
  }

  public MapClient map() {
    return map;
  }

  public ProgressEmitter progress() {
    return progress;
  }

  public void checkCancelled() {
    if (transport.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new ScriptCancelledException("Script execution was cancelled");
    }
  }

  public Path resolve(String relativePath) {
    Path value = workspace.resolve(relativePath).normalize();
    if (!value.startsWith(workspace)) throw new IllegalArgumentException("Path escapes workspace");
    return value;
  }
}
