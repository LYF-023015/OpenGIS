package org.opengis.common.protocol;

/** JSON-RPC request DTO. */
public record JsonRpcRequest(String jsonrpc, Object id, String method, Object params) {
  public JsonRpcRequest(Object id, String method, Object params) {
    this(ProtocolVersion.JSON_RPC, id, method, params);
  }
}
