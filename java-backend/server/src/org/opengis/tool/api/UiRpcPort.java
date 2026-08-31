/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.api;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import tools.jackson.databind.JsonNode;

/** Replaceable Java-to-Renderer port used by approvals and visual tools. */
public interface UiRpcPort {
  CompletionStage<JsonNode> request(String method, JsonNode params, Duration timeout);

  void notify(String method, JsonNode params);

  static UiRpcPort disconnected() {
    return new UiRpcPort() {
      @Override
      public CompletionStage<JsonNode> request(String method, JsonNode params, Duration timeout) {
        return java.util.concurrent.CompletableFuture.failedFuture(
            new IllegalStateException("Renderer is not connected"));
      }

      @Override
      public void notify(String method, JsonNode params) {
        throw new IllegalStateException("Renderer is not connected");
      }
    };
  }
}
