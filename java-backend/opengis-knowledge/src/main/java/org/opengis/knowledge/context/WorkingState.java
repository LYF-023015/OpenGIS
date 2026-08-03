package org.opengis.knowledge.context;

import java.util.List;
import java.util.Map;

/** Small mutable-task snapshot, separate from durable memory and raw history. */
public record WorkingState(
    String goal, List<String> completed, List<String> pending, Map<String, String> artifacts) {
  public WorkingState {
    goal = goal == null ? "" : goal;
    completed = completed == null ? List.of() : List.copyOf(completed);
    pending = pending == null ? List.of() : List.copyOf(pending);
    artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
  }

  public static WorkingState empty() {
    return new WorkingState("", List.of(), List.of(), Map.of());
  }
}
