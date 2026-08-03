package org.opengis.agent.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import org.opengis.tool.context.CancellationToken;

/** Owns active session/workspace leases and is the single cancellation lookup table. */
public final class SessionCoordinator {
  private final ConcurrentHashMap<String, ActiveRun> bySession = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Path, ActiveRun> byWorkspace = new ConcurrentHashMap<>();

  public SessionLease acquire(
      String sessionId,
      String runId,
      Path workspace,
      String connectionId,
      CancellationToken cancellation) {
    String session = normalizeSession(sessionId);
    Path root = workspace.toAbsolutePath().normalize();
    ActiveRun run =
        new ActiveRun(
            session,
            runId,
            root,
            connectionId == null ? "" : connectionId,
            cancellation,
            Instant.now(),
            null);
    ActiveRun sessionOwner = bySession.putIfAbsent(session, run);
    if (sessionOwner != null && !sessionOwner.runId().equals(runId)) {
      throw new SessionBusyException("Session is already running: " + sessionOwner.runId());
    }
    ActiveRun workspaceOwner = byWorkspace.putIfAbsent(root, run);
    if (workspaceOwner != null && !workspaceOwner.runId().equals(runId)) {
      bySession.remove(session, run);
      throw new SessionBusyException("Workspace is already running: " + workspaceOwner.runId());
    }
    return new SessionLease(this, run);
  }

  public void attachFuture(String runId, Future<?> future) {
    findRun(runId)
        .ifPresent(
            run -> {
              ActiveRun updated = run.withFuture(future);
              bySession.replace(run.sessionId(), run, updated);
              byWorkspace.replace(run.workspace(), run, updated);
            });
  }

  public boolean cancelRun(String runId) {
    Optional<ActiveRun> found = findRun(runId);
    found.ifPresent(SessionCoordinator::cancel);
    return found.isPresent();
  }

  public int cancelWorkspace(Path workspace) {
    ActiveRun run = byWorkspace.get(workspace.toAbsolutePath().normalize());
    if (run == null) {
      return 0;
    }
    cancel(run);
    return 1;
  }

  public int cancelConnection(String connectionId) {
    int count = 0;
    for (ActiveRun run : bySession.values()) {
      if (!connectionId.isBlank() && connectionId.equals(run.connectionId())) {
        cancel(run);
        count++;
      }
    }
    return count;
  }

  public Optional<ActiveRun> findRun(String runId) {
    return bySession.values().stream().filter(run -> run.runId().equals(runId)).findFirst();
  }

  public Map<String, ActiveRun> activeRuns() {
    return Map.copyOf(bySession);
  }

  private void release(ActiveRun run) {
    bySession.computeIfPresent(
        run.sessionId(),
        (ignored, current) -> current.runId().equals(run.runId()) ? null : current);
    byWorkspace.computeIfPresent(
        run.workspace(),
        (ignored, current) -> current.runId().equals(run.runId()) ? null : current);
  }

  private static void cancel(ActiveRun run) {
    run.cancellation().cancel();
  }

  private static String normalizeSession(String value) {
    return value == null || value.isBlank() ? "<no-session>" : value;
  }

  public record ActiveRun(
      String sessionId,
      String runId,
      Path workspace,
      String connectionId,
      CancellationToken cancellation,
      Instant startedAt,
      Future<?> future) {
    ActiveRun withFuture(Future<?> value) {
      return new ActiveRun(
          sessionId, runId, workspace, connectionId, cancellation, startedAt, value);
    }
  }

  public static final class SessionLease implements AutoCloseable {
    private final SessionCoordinator owner;
    private final ActiveRun run;
    private boolean closed;

    private SessionLease(SessionCoordinator owner, ActiveRun run) {
      this.owner = owner;
      this.run = run;
    }

    public ActiveRun run() {
      return run;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        owner.release(run);
      }
    }
  }
}
