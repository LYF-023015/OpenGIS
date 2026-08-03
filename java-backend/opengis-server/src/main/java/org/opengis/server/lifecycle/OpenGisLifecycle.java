package org.opengis.server.lifecycle;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Maps Spring lifecycle events to the stable Sidecar stdout contract. */
@Component
public class OpenGisLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpenGisLifecycle.class);

  private final StartupStateMachine stateMachine;
  private final WebSocketToken token;

  public OpenGisLifecycle(StartupStateMachine stateMachine, WebSocketToken token) {
    this.stateMachine = stateMachine;
    this.token = token;
  }

  @EventListener
  public void onStarted(ApplicationStartedEvent event) {
    stateMachine.transitionTo(StartupState.INITIALIZING);
    LOGGER.info("OpenGIS Java backend is initializing");
  }

  @EventListener
  public void onReady(ApplicationReadyEvent event) {
    System.out.println("OPENGIS_WS_TOKEN=" + token.value());
    stateMachine.transitionTo(StartupState.READY);
    System.out.println("OPENGIS_READY");
    System.out.flush();
    LOGGER.info("OpenGIS Java backend is ready");
  }

  @EventListener
  public void onClosing(ContextClosedEvent event) {
    StartupState state = stateMachine.current();
    if (state == StartupState.READY || state == StartupState.FAILED) {
      stateMachine.transitionTo(StartupState.STOPPING);
    }
    LOGGER.info("OpenGIS Java backend is stopping gracefully");
  }

  @PreDestroy
  public void onDestroyed() {
    if (stateMachine.current() == StartupState.STOPPING) {
      stateMachine.transitionTo(StartupState.STOPPED);
    }
  }
}
