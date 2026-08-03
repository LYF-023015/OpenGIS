package org.opengis.server.health;

import org.opengis.server.config.OpenGisRuntimeProperties;
import org.opengis.server.lifecycle.StartupState;
import org.opengis.server.lifecycle.StartupStateMachine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal compatibility endpoint; transport expansion belongs to Phase 2. */
@RestController
public class HealthController {
  private final StartupStateMachine stateMachine;
  private final OpenGisRuntimeProperties properties;

  public HealthController(StartupStateMachine stateMachine, OpenGisRuntimeProperties properties) {
    this.stateMachine = stateMachine;
    this.properties = properties;
  }

  @GetMapping("/api/health")
  public ResponseEntity<HealthResponse> health() {
    boolean ready = stateMachine.current() == StartupState.READY;
    HealthResponse response =
        new HealthResponse(ready ? "ok" : "starting", properties.getVersion());
    return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(response);
  }
}
