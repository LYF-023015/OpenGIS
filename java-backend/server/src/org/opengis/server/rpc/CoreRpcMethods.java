/** 文件职责：server 后端领域：接收外部请求并调用应用服务。 */
package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.opengis.core.protocol.JsonRpcErrorCodes;
import org.opengis.core.protocol.ProtocolVersion;
import org.springframework.stereotype.Component;

/** Phase 2 protocol methods plus explicit placeholders for later migration phases. */
@Component
public class CoreRpcMethods {
  private final RpcMethodRegistry registry;

  public CoreRpcMethods(RpcMethodRegistry registry) {
    this.registry = registry;
  }

  @PostConstruct
  void registerMethods() {
    registry.register(
        "rpc.system.ping",
        params ->
            Map.of(
                "status", "ok",
                "protocol_version", ProtocolVersion.OPENGIS,
                "runtime", "java"));
    registry.register("event.system.noop", params -> Map.of("accepted", true));
    LegacyMethodCatalog.BACKEND_METHODS.forEach(
        method ->
            registry.registerIfAbsent(
                method,
                params -> {
                  throw new RpcException(
                      JsonRpcErrorCodes.CAPABILITY_NOT_MIGRATED,
                      "Capability not migrated to Java yet",
                      Map.of("method", method, "planned_phase", plannedPhase(method)));
                }));
  }

  private static int plannedPhase(String method) {
    if (method.startsWith("rpc.tool.") || method.startsWith("rpc.fs.")) {
      return 4;
    }
    if (method.startsWith("rpc.agent.") || method.equals("chat.user_message")) {
      return 7;
    }
    if (method.startsWith("rpc.code.") || method.startsWith("rpc.worker.")) {
      return 6;
    }
    return 3;
  }
}
