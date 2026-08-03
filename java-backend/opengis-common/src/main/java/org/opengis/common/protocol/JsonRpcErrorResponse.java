package org.opengis.common.protocol;

/** Failed JSON-RPC response DTO. */
public record JsonRpcErrorResponse(String jsonrpc, Object id, JsonRpcErrorObject error) {
  public JsonRpcErrorResponse(Object id, int code, String message, Object data) {
    this(ProtocolVersion.JSON_RPC, id, new JsonRpcErrorObject(code, message, data));
  }
}
