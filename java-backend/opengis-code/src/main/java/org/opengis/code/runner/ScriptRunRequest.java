package org.opengis.code.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opengis.code.dependency.DependencyResolver;
import org.opengis.tool.context.CancellationToken;

/** Immutable parent-side request for one Java child process. */
public record ScriptRunRequest(
    Path workspace,
    String runId,
    String semanticName,
    String entryClass,
    String source,
    Map<String, Object> parameters,
    Set<String> permissions,
    List<DependencyResolver.Request> dependencies,
    boolean offline,
    Duration timeout,
    int maxHeapMb,
    CancellationToken cancellation) {
  public ScriptRunRequest {
    workspace = workspace.toAbsolutePath().normalize();
    runId = runId == null || runId.isBlank() ? "script-" + java.util.UUID.randomUUID() : runId;
    semanticName = semanticName == null || semanticName.isBlank() ? "java-script" : semanticName;
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
    maxHeapMb = Math.max(64, Math.min(maxHeapMb <= 0 ? 256 : maxHeapMb, 2048));
    cancellation = cancellation == null ? new CancellationToken() : cancellation;
  }
}
