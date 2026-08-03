package org.opengis.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StartupStateMachineTest {

  @Test
  void followsTheHappyPathThroughGracefulShutdown() {
    StartupStateMachine stateMachine = new StartupStateMachine();

    stateMachine.transitionTo(StartupState.INITIALIZING);
    stateMachine.transitionTo(StartupState.READY);
    stateMachine.transitionTo(StartupState.STOPPING);
    stateMachine.transitionTo(StartupState.STOPPED);

    assertThat(stateMachine.current()).isEqualTo(StartupState.STOPPED);
  }

  @Test
  void rejectsAnIllegalTransition() {
    StartupStateMachine stateMachine = new StartupStateMachine();

    assertThatThrownBy(() -> stateMachine.transitionTo(StartupState.READY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STARTING -> READY");
  }
}
