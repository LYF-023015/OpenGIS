/** 文件职责：gis 后端领域：封装外部系统或通信协议。 */
package org.opengis.gis.datasource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.opengis.core.concurrent.CancellationSignal;
import org.opengis.gis.error.GisException;

/** JDK HTTP adapter with explicit timeout, response-size and cancellation limits. */
public final class BoundedHttpClient {
  private volatile HttpClient client;
  private final int maxBytes;

  public BoundedHttpClient(int maxBytes) {
    this.maxBytes = Math.max(1024, maxBytes);
  }

  public byte[] get(URI uri, Duration timeout, CancellationSignal cancellation) {
    requireHttps(uri);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("accept", "application/json, application/geo+json")
            .header("user-agent", "OpenGIS-Java/0.1 (+https://github.com/OpenGIS)")
            .GET()
            .build();
    return send(request, cancellation);
  }

  public byte[] postForm(URI uri, String body, Duration timeout, CancellationSignal cancellation) {
    requireHttps(uri);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("accept", "application/json")
            .header("user-agent", "OpenGIS-Java/0.1 (+https://github.com/OpenGIS)")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return send(request, cancellation);
  }

  private byte[] send(HttpRequest request, CancellationSignal cancellation) {
    try {
      cancellation.throwIfCancelled();
      HttpResponse<InputStream> response =
          client().send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        response.body().close();
        throw new GisException(
            "gis_http_error", "Remote GIS service returned HTTP " + response.statusCode());
      }
      try (InputStream input = response.body();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
          cancellation.throwIfCancelled();
          total += read;
          if (total > maxBytes) {
            throw new GisException(
                "gis_response_too_large", "Remote GIS response exceeds " + maxBytes + " bytes");
          }
          output.write(buffer, 0, read);
        }
        return output.toByteArray();
      }
    } catch (GisException exception) {
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GisException("tool_cancelled", "GIS HTTP request was interrupted", exception);
    } catch (Exception exception) {
      throw new GisException("gis_network_error", "GIS HTTP request failed", exception);
    }
  }

  private HttpClient client() {
    HttpClient current = client;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (client == null) {
        client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
      }
      return client;
    }
  }

  private static void requireHttps(URI uri) {
    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
      throw new GisException("unsafe_gis_url", "GIS network adapters require an HTTPS URL");
    }
  }
}
