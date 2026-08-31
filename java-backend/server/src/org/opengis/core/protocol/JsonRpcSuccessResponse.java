/** 文件职责：common 后端领域：定义领域数据结构与协议。 */
package org.opengis.core.protocol;

/** Successful JSON-RPC response DTO. */
public record JsonRpcSuccessResponse(String jsonrpc, Object id, Object result) {
  public JsonRpcSuccessResponse(Object id, Object result) {
    this(ProtocolVersion.JSON_RPC, id, result);
  }
}
