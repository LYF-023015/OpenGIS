package org.opengis.common.protocol;

/** JSON-RPC notification DTO; notifications intentionally have no id. */
public record JsonRpcNotification(String jsonrpc, String method, Object params) {
  public JsonRpcNotification(String method, Object params) {
    this(ProtocolVersion.JSON_RPC, method, params);
  }
}
