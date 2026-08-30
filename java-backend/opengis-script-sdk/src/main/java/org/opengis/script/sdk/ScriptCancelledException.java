package org.opengis.script.sdk;

/** Cooperative cancellation signal exposed to script authors. */
public final class ScriptCancelledException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ScriptCancelledException(String message) {
    super(message);
  }
}
