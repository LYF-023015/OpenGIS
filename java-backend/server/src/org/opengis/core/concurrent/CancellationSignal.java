/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.concurrent;

/** Cooperative cancellation boundary shared by providers, agents, tools, and processes. */
public interface CancellationSignal {
  CancellationSignal NONE = () -> false;

  boolean isCancelled();

  default void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new OperationCancelledException();
    }
  }

  /** Registers cleanup which should run as soon as cancellation is requested. */
  default AutoCloseable onCancel(Runnable cleanup) {
    if (isCancelled()) {
      cleanup.run();
    }
    return () -> {};
  }
}
