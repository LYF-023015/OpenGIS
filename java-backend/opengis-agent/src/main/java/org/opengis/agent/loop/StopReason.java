package org.opengis.agent.loop;

public enum StopReason {
  COMPLETED,
  CANCELLED,
  PROVIDER_TIMEOUT,
  TOOL_TIMEOUT,
  STEP_LIMIT,
  REPEATED_FAILURE,
  DEVIATION,
  ERROR
}
