/** 文件职责：gis 后端领域：验证对应功能的行为与边界。 */
package org.opengis.gis.qgis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.opengis.core.concurrent.CancellationSource;
import tools.jackson.databind.ObjectMapper;

class QgisClientTest {
  @Test
  void usesBigEndianLengthPrefixedProtocol() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (ServerSocket server = new ServerSocket(0)) {
      CompletableFuture<String> received =
          CompletableFuture.supplyAsync(
              () -> {
                try (var socket = server.accept()) {
                  DataInputStream input = new DataInputStream(socket.getInputStream());
                  byte[] request = input.readNBytes(input.readInt());
                  String command = mapper.readTree(request).path("type").asString();
                  byte[] response =
                      mapper.writeValueAsBytes(
                          Map.of("status", "ok", "result", Map.of("connected", true)));
                  DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                  output.writeInt(response.length);
                  output.write(response);
                  output.flush();
                  return command;
                } catch (Exception exception) {
                  throw new RuntimeException(exception);
                }
              });
      QgisClient client =
          new QgisClient(mapper, "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));
      assertThat(
              client
                  .call("ping", mapper.createObjectNode(), new CancellationSource())
                  .path("connected")
                  .asBoolean())
          .isTrue();
      assertThat(received.join()).isEqualTo("ping");
    }
  }
}
