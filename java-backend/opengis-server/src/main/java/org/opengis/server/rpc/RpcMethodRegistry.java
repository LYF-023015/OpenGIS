package org.opengis.server.rpc;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Thread-safe method table shared by HTTP and WebSocket transports. */
@Component
public class RpcMethodRegistry {
  private final Map<String, RpcHandler> handlers = new ConcurrentHashMap<>();

  public void register(String method, RpcHandler handler) {
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("RPC method must not be blank");
    }
    if (handlers.putIfAbsent(method, handler) != null) {
      throw new IllegalStateException("RPC method already registered: " + method);
    }
  }

  public void registerIfAbsent(String method, RpcHandler handler) {
    validateMethod(method);
    handlers.putIfAbsent(method, handler);
  }

  public void registerOrReplace(String method, RpcHandler handler) {
    validateMethod(method);
    handlers.put(method, handler);
  }

  public Optional<RpcHandler> find(String method) {
    return Optional.ofNullable(handlers.get(method));
  }

  public int size() {
    return handlers.size();
  }

  private static void validateMethod(String method) {
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("RPC method must not be blank");
    }
  }
}
