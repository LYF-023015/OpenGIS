package org.opengis.ai.port;

import org.opengis.ai.model.LlmError;

/** Provider-neutral failure exposed to retry and runtime-control policies. */
public final class LlmException extends RuntimeException {
  private final LlmError error;

  public LlmException(LlmError error) {
    super(error.message());
    this.error = error;
  }

  public LlmException(LlmError error, Throwable cause) {
    super(error.message(), cause);
    this.error = error;
  }

  public LlmError error() {
    return error;
  }
}
