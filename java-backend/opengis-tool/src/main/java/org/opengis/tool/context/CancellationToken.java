package org.opengis.tool.context;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opengis.framework.concurrent.CancellationSignal;
import org.opengis.tool.api.ToolException;

/** Explicit cooperative cancellation shared by Java tools and child processes. */
public final class CancellationToken implements CancellationSignal {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

  public void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      listeners.forEach(CancellationToken::runQuietly);
      listeners.clear();
    }
  }

  public boolean isCancelled() {
    return cancelled.get();
  }

  @Override
  public AutoCloseable onCancel(Runnable cleanup) {
    if (isCancelled()) {
      runQuietly(cleanup);
      return () -> {};
    }
    listeners.add(cleanup);
    if (isCancelled() && listeners.remove(cleanup)) {
      runQuietly(cleanup);
    }
    return () -> listeners.remove(cleanup);
  }

  public void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new ToolException("tool_cancelled", "Tool execution was cancelled");
    }
  }

  private static void runQuietly(Runnable listener) {
    try {
      listener.run();
    } catch (RuntimeException ignored) {
      // Cancellation cleanup is best effort; one listener must not block the others.
    }
  }
}
