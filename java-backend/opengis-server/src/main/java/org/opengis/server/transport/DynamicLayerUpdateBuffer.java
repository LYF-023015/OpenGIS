package org.opengis.server.transport;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Coalesces dynamic layer bursts while preserving every incremental diff in order. */
@Component
public class DynamicLayerUpdateBuffer {
  public static final String METHOD = "rpc.ui.map.dynamic_layer_update";
  private static final long FRAME_MILLIS = 100;
  private static final Logger LOGGER = LoggerFactory.getLogger(DynamicLayerUpdateBuffer.class);

  private final Map<BufferKey, List<Object>> pending = new LinkedHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "opengis-dynamic-layer-buffer");
            thread.setDaemon(true);
            return thread;
          });
  private boolean flushScheduled;

  public synchronized void enqueue(RpcConnection connection, Map<String, Object> params) {
    Object rawLayerId = params.get("layer_id");
    String layerId = rawLayerId instanceof String value && !value.isBlank() ? value : "__unknown__";
    BufferKey key = new BufferKey(connection, layerId);
    List<Object> existing = pending.computeIfAbsent(key, ignored -> new ArrayList<>());
    if (isFullFrame(params)) {
      existing.clear();
    }
    existing.add(params);
    if (!flushScheduled) {
      flushScheduled = true;
      scheduler.schedule(this::flushNow, FRAME_MILLIS, TimeUnit.MILLISECONDS);
    }
  }

  synchronized void clearConnection(String connectionId) {
    pending.keySet().removeIf(key -> key.connection().id().equals(connectionId));
  }

  public void flushNow() {
    Map<BufferKey, List<Object>> batch;
    synchronized (this) {
      batch = new LinkedHashMap<>(pending);
      pending.clear();
      flushScheduled = false;
    }
    batch.forEach(
        (key, updates) ->
            updates.forEach(
                params -> {
                  try {
                    key.connection().notify(METHOD, params);
                  } catch (RuntimeException exception) {
                    LOGGER.debug("Dynamic update target closed: {}", key.connection().id());
                  }
                }));
  }

  private static boolean isFullFrame(Map<String, Object> params) {
    return "full".equals(params.get("mode"))
        || (params.get("geojson") != null && params.get("diff") == null);
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
    synchronized (this) {
      pending.clear();
    }
  }

  private record BufferKey(RpcConnection connection, String layerId) {}
}
