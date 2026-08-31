/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.context;

import org.opengis.core.concurrent.CancellationSignal;
import org.opengis.core.concurrent.CancellationSource;
import org.opengis.tool.api.ToolException;

/** Explicit cooperative cancellation shared by Java tools and child processes. */
public final class CancellationToken implements CancellationSignal {
  private final CancellationSource source = new CancellationSource();

  public void cancel() {
    source.cancel();
  }

  public boolean isCancelled() {
    return source.isCancelled();
  }

  @Override
  public AutoCloseable onCancel(Runnable cleanup) {
    return source.onCancel(cleanup);
  }

  @Override
  public void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new ToolException("tool_cancelled", "Tool execution was cancelled");
    }
  }
}
