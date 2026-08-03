package org.opengis.common.protocol;

/** Successful JSON-RPC response DTO. */
public record JsonRpcSuccessResponse(String jsonrpc, Object id, Object result) {
  public JsonRpcSuccessResponse(Object id, Object result) {
    this(ProtocolVersion.JSON_RPC, id, result);
  }
}
