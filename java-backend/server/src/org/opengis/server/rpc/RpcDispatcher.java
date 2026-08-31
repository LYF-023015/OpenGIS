/** 文件职责：server 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.server.rpc;

import java.util.Map;
import org.opengis.core.protocol.JsonRpcErrorCodes;
import org.opengis.core.protocol.JsonRpcErrorResponse;
import org.opengis.core.protocol.JsonRpcSuccessResponse;
import org.opengis.core.protocol.ProtocolVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transport-neutral JSON-RPC 2.0 parser, validator and dispatcher. */
@Component
public class RpcDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(RpcDispatcher.class);

  private final ObjectMapper objectMapper;
  private final RpcMethodRegistry registry;

  public RpcDispatcher(ObjectMapper objectMapper, RpcMethodRegistry registry) {
    this.objectMapper = objectMapper;
    this.registry = registry;
  }

  public Object dispatch(String payload) {
    final JsonNode message;
    try {
      message = objectMapper.readTree(payload);
    } catch (JacksonException exception) {
      return error(null, JsonRpcErrorCodes.PARSE_ERROR, "Parse error", null);
    }
    return dispatch(message);
  }

  public Object dispatch(JsonNode message) {
    if (message == null || !message.isObject()) {
      return error(null, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request", null);
    }

    boolean hasId = message.has("id");
    Object id = hasId ? rpcId(message.get("id")) : null;
    if (hasId && id == InvalidId.VALUE) {
      return error(null, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request", "Invalid id");
    }
    if (!ProtocolVersion.JSON_RPC.equals(message.path("jsonrpc").asString())
        || !message.path("method").isString()
        || message.path("method").asString().isBlank()) {
      return error(id, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid Request", null);
    }

    JsonNode params = message.get("params");
    if (params != null && !params.isNull() && !params.isObject() && !params.isArray()) {
      return hasId ? error(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid params", null) : null;
    }

    String method = message.path("method").asString();
    RpcHandler handler = registry.find(method).orElse(null);
    if (handler == null) {
      return hasId
          ? error(
              id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found", Map.of("method", method))
          : null;
    }

    try {
      Object result = handler.handle(params == null ? objectMapper.createObjectNode() : params);
      return hasId ? new JsonRpcSuccessResponse(id, result) : null;
    } catch (RpcException exception) {
      return hasId ? error(id, exception.code(), exception.getMessage(), exception.data()) : null;
    } catch (RuntimeException exception) {
      LOGGER.error("Unhandled JSON-RPC method failure: {}", method, exception);
      return hasId ? error(id, JsonRpcErrorCodes.INTERNAL_ERROR, "Internal error", null) : null;
    }
  }

  private static JsonRpcErrorResponse error(Object id, int code, String message, Object data) {
    return new JsonRpcErrorResponse(id, code, message, data);
  }

  private static Object rpcId(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    if (node.isFloatingPointNumber()) {
      return node.doubleValue();
    }
    return InvalidId.VALUE;
  }

  private enum InvalidId {
    VALUE
  }
}
