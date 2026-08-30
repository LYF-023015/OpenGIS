package org.opengis.server.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.agent.session.SessionCoordinator;
import org.opengis.server.lifecycle.WebSocketToken;
import org.opengis.server.rpc.RpcDispatcher;
import org.opengis.tool.context.CancellationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class WebSocketAgentCancellationTest {
  @Test
  @SuppressWarnings("try") // The acquired session is a lifetime guard for this test.
  void closingRendererConnectionCancelsItsAgentRun(@TempDir Path workspace) {
    SessionCoordinator coordinator = new SessionCoordinator();
    CancellationToken cancellation = new CancellationToken();
    try (var ignored =
        coordinator.acquire("conversation", "run", workspace, "ws-1", cancellation)) {
      OpenGisWebSocketHandler handler =
          new OpenGisWebSocketHandler(
              new WebSocketToken(),
              mock(RpcDispatcher.class),
              mock(RpcConnectionManager.class),
              mock(DynamicLayerUpdateBuffer.class),
              coordinator,
              new ObjectMapper());
      WebSocketSession session = mock(WebSocketSession.class);
      when(session.getId()).thenReturn("ws-1");

      handler.afterConnectionClosed(session, CloseStatus.NORMAL);

      assertThat(cancellation.isCancelled()).isTrue();
    }
  }
}
