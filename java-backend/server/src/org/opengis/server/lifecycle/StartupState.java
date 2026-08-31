/** 文件职责：server 后端领域：实现该文件名所对应的单一职责。 */
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
