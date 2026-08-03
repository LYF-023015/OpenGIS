package org.opengis.framework.concurrent;

/** Cooperative cancellation boundary shared by providers, agents, tools, and processes. */
public interface CancellationSignal {
  CancellationSignal NONE = () -> false;

  boolean isCancelled();

  /** Registers cleanup which should run as soon as cancellation is requested. */
  default AutoCloseable onCancel(Runnable cleanup) {
    if (isCancelled()) {
      cleanup.run();
    }
    return () -> {};
  }
}
