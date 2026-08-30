package org.opengis.server.rpc;

/** Exception intentionally exposed as a structured JSON-RPC error. */
public class RpcException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int code;
  private final transient Object data;

  public RpcException(int code, String message) {
    this(code, message, null);
  }

  public RpcException(int code, String message, Object data) {
    super(message);
    this.code = code;
    this.data = data;
  }

  public int code() {
    return code;
  }

  public Object data() {
    return data;
  }
}
