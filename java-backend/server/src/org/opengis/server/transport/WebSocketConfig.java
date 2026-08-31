/** 文件职责：server 后端领域：集中声明运行配置。 */
package org.opengis.server.transport;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the loopback-only desktop WebSocket endpoint. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final OpenGisWebSocketHandler handler;

  public WebSocketConfig(OpenGisWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/ws")
        .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "file://");
  }
}
