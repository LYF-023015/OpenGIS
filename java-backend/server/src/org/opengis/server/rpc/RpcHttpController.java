/** 文件职责：server 后端领域：接收外部请求并调用应用服务。 */
package org.opengis.server.rpc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP bridge used by diagnostics and clients that cannot open a WebSocket. */
@RestController
public class RpcHttpController {
  private final RpcDispatcher dispatcher;

  public RpcHttpController(RpcDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @PostMapping(path = "/api/rpc", consumes = "application/json", produces = "application/json")
  public ResponseEntity<Object> dispatch(@RequestBody String body) {
    Object response = dispatcher.dispatch(body);
    return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
  }
}
