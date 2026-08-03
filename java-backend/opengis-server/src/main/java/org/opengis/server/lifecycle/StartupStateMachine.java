package org.opengis.server.lifecycle;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Thread-safe lifecycle state with explicit legal transitions. */
@Component
public class StartupStateMachine {
  private static final Map<StartupState, Set<StartupState>> ALLOWED =
      Map.of(
          StartupState.STARTING,
          EnumSet.of(StartupState.INITIALIZING, StartupState.FAILED),
          StartupState.INITIALIZING,
          EnumSet.of(StartupState.READY, StartupState.FAILED),
          StartupState.READY,
          EnumSet.of(StartupState.STOPPING, StartupState.FAILED),
          StartupState.STOPPING,
          EnumSet.of(StartupState.STOPPED),
          StartupState.STOPPED,
          EnumSet.noneOf(StartupState.class),
          StartupState.FAILED,
          EnumSet.of(StartupState.STOPPING, StartupState.STOPPED));

  private final AtomicReference<StartupState> state = new AtomicReference<>(StartupState.STARTING);

  public StartupState current() {
    return state.get();
  }

  public void transitionTo(StartupState target) {
    while (true) {
      StartupState current = state.get();
      if (current == target) {
        return;
      }
      if (!ALLOWED.get(current).contains(target)) {
        throw new IllegalStateException("Illegal startup transition: " + current + " -> " + target);
      }
      if (state.compareAndSet(current, target)) {
        return;
      }
    }
  }
}
