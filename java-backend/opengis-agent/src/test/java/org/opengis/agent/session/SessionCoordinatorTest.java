package org.opengis.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.tool.context.CancellationToken;

class SessionCoordinatorTest {
  @Test
  void preventsConcurrentSessionAndWorkspaceOwners(@TempDir Path workspace) {
    SessionCoordinator coordinator = new SessionCoordinator();
    try (var ignored =
        coordinator.acquire(
            "conversation", "run-1", workspace, "connection", new CancellationToken())) {
      assertThatThrownBy(
              () ->
                  coordinator.acquire(
                      "conversation", "run-2", workspace, "connection", new CancellationToken()))
          .isInstanceOf(SessionBusyException.class);
      assertThat(coordinator.activeRuns()).hasSize(1);
    }
    assertThat(coordinator.activeRuns()).isEmpty();
  }

  @Test
  void websocketDisconnectCancelsOnlyOwnedRuns(@TempDir Path workspace) {
    SessionCoordinator coordinator = new SessionCoordinator();
    CancellationToken first = new CancellationToken();
    CancellationToken second = new CancellationToken();
    try (var ignoredFirst =
            coordinator.acquire("one", "run-1", workspace.resolve("one"), "a", first);
        var ignoredSecond =
            coordinator.acquire("two", "run-2", workspace.resolve("two"), "b", second)) {
      assertThat(coordinator.cancelConnection("a")).isEqualTo(1);
      assertThat(first.isCancelled()).isTrue();
      assertThat(second.isCancelled()).isFalse();
    }
  }
}
