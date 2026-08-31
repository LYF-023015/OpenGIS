/** 文件职责：common 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.protocol;

/** JSON-RPC notification DTO; notifications intentionally have no id. */
public record JsonRpcNotification(String jsonrpc, String method, Object params) {
  public JsonRpcNotification(String method, Object params) {
    this(ProtocolVersion.JSON_RPC, method, params);
  }
}
