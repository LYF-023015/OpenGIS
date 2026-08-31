/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenGisApplicationTest {

  @Test
  void mapsLegacyPythonSidecarArgumentsToSpringProperties() {
    String[] normalized =
        OpenGisApplication.normalizeLegacyArguments(
            new String[] {"--host", "127.0.0.2", "--port=19000", "--log-dir", "build/logs"});

    assertThat(normalized)
        .containsExactly(
            "--server.address=127.0.0.2",
            "--server.port=19000",
            "--opengis.runtime.log-dir=build/logs",
            "--logging.file.path=build/logs");
  }
}
