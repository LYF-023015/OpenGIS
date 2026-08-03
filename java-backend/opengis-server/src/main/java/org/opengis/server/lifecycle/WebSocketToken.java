package org.opengis.server.lifecycle;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Per-process token reserved for the Phase 2 WebSocket authentication layer. */
@Component
public class WebSocketToken {
  private static final int TOKEN_BYTES = 32;
  private final String value;

  public WebSocketToken() {
    byte[] random = new byte[TOKEN_BYTES];
    new SecureRandom().nextBytes(random);
    value = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  public String value() {
    return value;
  }
}
