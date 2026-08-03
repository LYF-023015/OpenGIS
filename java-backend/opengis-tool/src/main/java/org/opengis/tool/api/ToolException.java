package org.opengis.tool.api;

/** Expected execution failure with a stable machine code. */
public class ToolException extends RuntimeException {
  private final String code;
  private final boolean retryable;

  public ToolException(String code, String message) {
    this(code, message, false, null);
  }

  public ToolException(String code, String message, Throwable cause) {
    this(code, message, false, cause);
  }

  public ToolException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
