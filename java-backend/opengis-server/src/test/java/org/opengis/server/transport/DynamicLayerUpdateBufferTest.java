package org.opengis.server.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DynamicLayerUpdateBufferTest {
  @Test
  void fullFrameSupersedesOlderFramesAndLaterDiffsKeepOrder() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    List<String> sent = new ArrayList<>();
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("renderer-dynamic");
    when(session.isOpen()).thenReturn(true);
    doAnswer(
            invocation -> {
              sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
              return null;
            })
        .when(session)
        .sendMessage(any(TextMessage.class));
    RpcConnection connection = new RpcConnection(session, objectMapper);
    DynamicLayerUpdateBuffer buffer = new DynamicLayerUpdateBuffer();

    buffer.enqueue(connection, Map.of("layer_id", "cities", "mode", "diff", "sequence", 0));
    buffer.enqueue(
        connection,
        Map.of("layer_id", "cities", "mode", "full", "geojson", Map.of(), "sequence", 1));
    buffer.enqueue(connection, Map.of("layer_id", "cities", "mode", "diff", "sequence", 2));
    buffer.enqueue(connection, Map.of("layer_id", "cities", "mode", "diff", "sequence", 3));
    buffer.flushNow();

    List<Integer> sequences = new ArrayList<>();
    for (String payload : sent) {
      JsonNode message = objectMapper.readTree(payload);
      assertThat(message.path("method").asString()).isEqualTo(DynamicLayerUpdateBuffer.METHOD);
      sequences.add(message.path("params").path("sequence").asInt());
    }
    assertThat(sequences).containsExactly(1, 2, 3);
    buffer.shutdown();
  }
}
