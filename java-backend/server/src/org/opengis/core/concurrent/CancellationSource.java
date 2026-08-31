/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.concurrent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mutable owner for a cooperative {@link CancellationSignal}. */
public final class CancellationSource implements CancellationSignal {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

  public void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      listeners.forEach(CancellationSource::runQuietly);
      listeners.clear();
    }
  }

  @Override
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

  private static void runQuietly(Runnable listener) {
    try {
      listener.run();
    } catch (RuntimeException ignored) {
      // Cancellation cleanup is best effort; one listener must not block the others.
    }
  }
}
