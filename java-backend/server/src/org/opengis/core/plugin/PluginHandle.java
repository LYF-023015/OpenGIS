/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent reversible effect returned by plugin mounting and capability registration. */
@FunctionalInterface
public interface PluginHandle extends AutoCloseable {
  @Override
  void close();

  static PluginHandle noop() {
    return () -> {};
  }

  /** Closes child handles once, in reverse registration order. */
  static PluginHandle combine(Collection<? extends PluginHandle> handles) {
    List<PluginHandle> owned = new ArrayList<>(handles);
    AtomicBoolean closed = new AtomicBoolean();
    return () -> {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      RuntimeException failure = null;
      for (int index = owned.size() - 1; index >= 0; index--) {
        try {
          owned.get(index).close();
        } catch (RuntimeException exception) {
          if (failure == null) {
            failure = new PluginLifecycleException("Failed to close plugin effects", exception);
          } else {
            failure.addSuppressed(exception);
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    };
  }
}
