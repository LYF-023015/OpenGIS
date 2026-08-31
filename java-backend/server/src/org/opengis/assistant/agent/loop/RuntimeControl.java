/** 文件职责：agent 后端领域：可复用界面组件。 */
package org.opengis.assistant.agent.loop;

import java.time.Duration;
import java.time.Instant;
import org.opengis.assistant.agent.profile.AgentProfile;
import org.opengis.assistant.memory.FailureMemory;
import org.opengis.tool.context.CancellationToken;

/** Central bounded-runtime rules; every provider and tool turn passes through here. */
public final class RuntimeControl {
  private final int maxProviderTurns;
  private final int maxToolSteps;
  private final int repeatedFailureLimit;
  private final Duration maxDuration;
  private final Duration toolTimeout;
  private final CancellationToken cancellation;
  private final FailureMemory failures;
  private final Instant startedAt = Instant.now();
  private int providerTurns;
  private int toolSteps;

  public RuntimeControl(
      AgentProfile profile,
      Duration maxDuration,
      Duration toolTimeout,
      int repeatedFailureLimit,
      CancellationToken cancellation) {
    maxProviderTurns = profile.limit("max_provider_turns", profile.maxSteps());
    maxToolSteps = profile.limit("max_tool_steps", profile.maxSteps() * 2);
    this.repeatedFailureLimit = Math.max(2, repeatedFailureLimit);
    this.maxDuration = maxDuration == null ? Duration.ofMinutes(10) : maxDuration;
    this.toolTimeout = toolTimeout == null ? Duration.ofMinutes(10) : toolTimeout;
    this.cancellation = cancellation;
    failures = new FailureMemory(32);
  }

  public void beforeProviderTurn() {
    checkRuntime();
    if (providerTurns >= maxProviderTurns) {
      throw new AgentStopException(StopReason.STEP_LIMIT, "Maximum provider turns reached");
    }
    providerTurns++;
  }

  public void beforeTool() {
    checkRuntime();
    if (toolSteps >= maxToolSteps) {
      throw new AgentStopException(StopReason.STEP_LIMIT, "Maximum tool steps reached");
    }
    toolSteps++;
  }

  /** Records the single no-tool provider call reserved for a bounded final response. */
  public void recordFinalizationTurn() {
    providerTurns++;
  }

  public void recordFailure(String operation, String error) {
    FailureMemory.FailureRecord failure = failures.record(operation, error);
    if (failure.count() >= repeatedFailureLimit) {
      throw new AgentStopException(
          StopReason.REPEATED_FAILURE,
          "Repeated identical failure for " + operation + " (" + failure.count() + " times)");
    }
  }

  public Duration toolTimeout() {
    return toolTimeout;
  }

  public int providerTurns() {
    return providerTurns;
  }

  public int toolSteps() {
    return toolSteps;
  }

  private void checkRuntime() {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new AgentStopException(StopReason.CANCELLED, "Agent run was cancelled");
    }
    if (Duration.between(startedAt, Instant.now()).compareTo(maxDuration) > 0) {
      cancellation.cancel();
      throw new AgentStopException(StopReason.CANCELLED, "Agent runtime deadline exceeded");
    }
  }
}
