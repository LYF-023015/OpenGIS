package org.opengis.ai.model;

/** Safe provider error shape; secrets and raw request headers are never included. */
public record LlmError(String code, String message, boolean retryable, int httpStatus) {
  public LlmError {
    code = code == null || code.isBlank() ? "provider_error" : code;
    message = message == null || message.isBlank() ? "LLM provider request failed" : message;
  }
}
