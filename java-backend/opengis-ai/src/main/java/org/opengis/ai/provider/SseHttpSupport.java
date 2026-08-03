package org.opengis.ai.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.opengis.ai.model.LlmError;
import org.opengis.ai.port.LlmException;
import org.opengis.framework.concurrent.CancellationSignal;

final class SseHttpSupport {
  private static final int MAX_ERROR_CHARS = 4000;

  private SseHttpSupport() {}

  static void exchange(
      HttpClient client,
      HttpRequest request,
      Duration timeout,
      CancellationSignal cancellation,
      Consumer<String> onData) {
    var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    try (AutoCloseable ignored = cancellation.onCancel(() -> future.cancel(true))) {
      HttpResponse<InputStream> response =
          future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String body = readLimited(response.body());
        throw httpError(response.statusCode(), body);
      }
      try (InputStream stream = response.body();
          AutoCloseable closeOnCancel = cancellation.onCancel(() -> closeQuietly(stream));
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          checkCancellation(cancellation);
          if (line.startsWith("data:")) {
            onData.accept(line.substring(5).trim());
          }
        }
        checkCancellation(cancellation);
      }
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new LlmException(
          new LlmError("provider_timeout", "LLM provider request timed out", true, 0), exception);
    } catch (CancellationException exception) {
      throw cancelled(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw cancelled(exception);
    } catch (ExecutionException exception) {
      if (cancellation.isCancelled()) {
        throw cancelled(exception);
      }
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      if (cause instanceof java.net.http.HttpTimeoutException) {
        throw timeout(cause);
      }
      throw transportError(cause);
    } catch (IOException exception) {
      if (cancellation.isCancelled()) {
        throw cancelled(exception);
      }
      throw transportError(exception);
    } catch (LlmException exception) {
      throw exception;
    } catch (Exception exception) {
      throw transportError(exception);
    }
  }

  static void checkCancellation(CancellationSignal cancellation) {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw cancelled(null);
    }
  }

  private static LlmException httpError(int status, String body) {
    boolean retryable = status == 408 || status == 429 || status >= 500;
    String message = body.isBlank() ? "LLM provider returned HTTP " + status : body;
    return new LlmException(new LlmError("provider_http_error", message, retryable, status));
  }

  private static LlmException transportError(Throwable cause) {
    String message = cause.getMessage();
    if (message == null || message.isBlank()) {
      message = cause.getClass().getSimpleName();
    }
    return new LlmException(new LlmError("provider_transport_error", message, true, 0), cause);
  }

  private static LlmException timeout(Throwable cause) {
    return new LlmException(
        new LlmError("provider_timeout", "LLM provider request timed out", true, 0), cause);
  }

  private static LlmException cancelled(Throwable cause) {
    LlmError error = new LlmError("llm_cancelled", "LLM request was cancelled", false, 0);
    return cause == null ? new LlmException(error) : new LlmException(error, cause);
  }

  private static String readLimited(InputStream stream) throws IOException {
    byte[] bytes = stream.readNBytes(MAX_ERROR_CHARS);
    return new String(bytes, StandardCharsets.UTF_8).strip();
  }

  private static void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // Closing an already failed stream is harmless.
    }
  }
}
