/** 文件职责：common 后端领域：定义领域数据结构与协议。 */
package org.opengis.core.protocol;

/** Failed JSON-RPC response DTO. */
public record JsonRpcErrorResponse(String jsonrpc, Object id, JsonRpcErrorObject error) {
  public JsonRpcErrorResponse(Object id, int code, String message, Object data) {
    this(ProtocolVersion.JSON_RPC, id, new JsonRpcErrorObject(code, message, data));
  }

  /** Structured JSON-RPC error payload. */
  public record JsonRpcErrorObject(int code, String message, Object data) {}
}
