package org.opengis.server.rpc;

import tools.jackson.databind.JsonNode;

/** A single Java-side JSON-RPC method implementation. */
@FunctionalInterface
public interface RpcHandler {
  Object handle(JsonNode params);
}
