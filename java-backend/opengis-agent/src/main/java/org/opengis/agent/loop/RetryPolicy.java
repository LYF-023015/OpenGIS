package org.opengis.agent.loop;

import java.time.Duration;
import java.util.concurrent.Callable;
import org.opengis.ai.port.LlmException;
import org.opengis.framework.concurrent.CancellationSignal;

/** Provider-network retry only; tool failures deliberately return to a fresh model turn. */
public final class RetryPolicy {
  private final int maxAttempts;
  private final Duration initialDelay;

  public RetryPolicy(int maxAttempts, Duration initialDelay) {
    this.maxAttempts = Math.max(1, maxAttempts);
    this.initialDelay = initialDelay == null ? Duration.ofMillis(100) : initialDelay;
  }

  public <T> T execute(Callable<T> operation, CancellationSignal cancellation) {
    LlmException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      if (cancellation.isCancelled()) {
        throw new AgentStopException(StopReason.CANCELLED, "Agent run was cancelled");
      }
      try {
        return operation.call();
      } catch (LlmException exception) {
        last = exception;
        if (!exception.error().retryable() || attempt == maxAttempts) {
          throw exception;
        }
        waitBeforeRetry(attempt, cancellation);
      } catch (AgentStopException exception) {
        throw exception;
      } catch (Exception exception) {
        throw new IllegalStateException("Provider operation failed", exception);
      }
    }
    throw last;
  }

  private void waitBeforeRetry(int attempt, CancellationSignal cancellation) {
    long millis = Math.min(2000, initialDelay.toMillis() * (1L << Math.min(10, attempt - 1)));
    long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
    while (System.nanoTime() < deadline) {
      if (cancellation.isCancelled()) {
        throw new AgentStopException(StopReason.CANCELLED, "Agent run was cancelled");
      }
      try {
        Thread.sleep(Math.min(50, millis));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AgentStopException(StopReason.CANCELLED, "Retry wait was interrupted");
      }
    }
  }
}
