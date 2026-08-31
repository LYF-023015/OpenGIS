/** 文件职责：common 后端领域：定义领域数据结构与协议。 */
package org.opengis.core.protocol;

/** JSON-RPC request DTO. */
public record JsonRpcRequest(String jsonrpc, Object id, String method, Object params) {
  public JsonRpcRequest(Object id, String method, Object params) {
    this(ProtocolVersion.JSON_RPC, id, method, params);
  }
}
