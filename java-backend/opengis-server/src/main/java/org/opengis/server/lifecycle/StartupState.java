package org.opengis.server.lifecycle;

/** Observable lifecycle states for deterministic Sidecar startup and shutdown. */
public enum StartupState {
  STARTING,
  INITIALIZING,
  READY,
  STOPPING,
  STOPPED,
  FAILED
}
