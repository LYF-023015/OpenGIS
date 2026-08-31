/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opengis.server.lifecycle.StartupState;
import org.opengis.server.lifecycle.StartupStateMachine;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class OpenGisApplicationIT {

  @Test
  void startsReadyAndClosesThroughTheLifecycleStateMachine() {
    StartupStateMachine stateMachine;
    try (ConfigurableApplicationContext context =
        SpringApplication.run(
            OpenGisApplication.class,
            "--server.port=0",
            "--logging.file.path=target/phase1-it-logs")) {
      stateMachine = context.getBean(StartupStateMachine.class);
      assertThat(stateMachine.current()).isEqualTo(StartupState.READY);
    }
    assertThat(stateMachine.current()).isEqualTo(StartupState.STOPPED);
  }
}
