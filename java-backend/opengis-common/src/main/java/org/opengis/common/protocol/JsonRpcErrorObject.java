package org.opengis.common.protocol;

/** Structured JSON-RPC error payload. */
public record JsonRpcErrorObject(int code, String message, Object data) {}
