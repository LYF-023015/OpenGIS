package org.opengis.workflow.queue;

/** Server adapter for starting/cancelling an Agent or Workflow run. */
public interface QueueRunPort {
  StartResult start(AgentQueueItem item, boolean resume);

  boolean cancel(AgentQueueItem item);

  /** Returns queued/running/success/error/cancelled, or unknown when not inspectable. */
  String status(AgentQueueItem item);

  record StartResult(boolean started, String runId, String error) {}
}
