package org.opengis.script.sdk;

/** Cooperative cancellation signal exposed to script authors. */
public final class ScriptCancelledException extends RuntimeException {
  public ScriptCancelledException(String message) {
    super(message);
  }
}
