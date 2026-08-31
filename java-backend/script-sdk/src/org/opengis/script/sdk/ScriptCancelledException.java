/** 文件职责：script 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.script.sdk;

/** Cooperative cancellation signal exposed to script authors. */
public final class ScriptCancelledException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ScriptCancelledException(String message) {
    super(message);
  }
}
