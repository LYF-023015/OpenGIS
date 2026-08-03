package org.opengis.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebSocketTokenTest {

  @Test
  void createsUrlSafePerProcessTokens() {
    String first = new WebSocketToken().value();
    String second = new WebSocketToken().value();

    assertThat(first).matches("[A-Za-z0-9_-]{43}");
    assertThat(second).isNotEqualTo(first);
  }
}
