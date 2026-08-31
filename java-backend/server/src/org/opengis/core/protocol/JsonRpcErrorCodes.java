/** 文件职责：common 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.protocol;

/** Standard JSON-RPC and OpenGIS-reserved error codes. */
public final class JsonRpcErrorCodes {
  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;
  public static final int INVALID_WEBSOCKET_TOKEN = -32001;
  public static final int AGENT_TIMEOUT = -32002;
  public static final int CAPABILITY_NOT_MIGRATED = -32004;
  public static final int SANDBOX_DENIED = -32010;
  public static final int SKILL_NOT_FOUND = -32020;
  public static final int SKILL_TIMEOUT = -32021;

  private JsonRpcErrorCodes() {}
}
