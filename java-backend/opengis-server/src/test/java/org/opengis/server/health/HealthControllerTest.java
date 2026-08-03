package org.opengis.server.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opengis.server.config.OpenGisRuntimeProperties;
import org.opengis.server.lifecycle.StartupState;
import org.opengis.server.lifecycle.StartupStateMachine;
import org.springframework.http.HttpStatus;

class HealthControllerTest {

  @Test
  void reportsStartingUntilTheApplicationIsReady() {
    StartupStateMachine stateMachine = new StartupStateMachine();
    OpenGisRuntimeProperties properties = new OpenGisRuntimeProperties();
    HealthController controller = new HealthController(stateMachine, properties);

    assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(controller.health().getBody()).isEqualTo(new HealthResponse("starting", "0.1.0"));

    stateMachine.transitionTo(StartupState.INITIALIZING);
    stateMachine.transitionTo(StartupState.READY);

    assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(controller.health().getBody()).isEqualTo(new HealthResponse("ok", "0.1.0"));
  }
}
