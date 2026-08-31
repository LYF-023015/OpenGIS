/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.plugin;

/** Signals invalid composition or a failure while mounting or disposing a plugin. */
public final class PluginLifecycleException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PluginLifecycleException(String message) {
    super(message);
  }

  public PluginLifecycleException(String message, Throwable cause) {
    super(message, cause);
  }
}
