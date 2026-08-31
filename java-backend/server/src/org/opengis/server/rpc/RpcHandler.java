/** 文件职责：server 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.server.rpc;

import tools.jackson.databind.JsonNode;

/** A single Java-side JSON-RPC method implementation. */
@FunctionalInterface
public interface RpcHandler {
  Object handle(JsonNode params);
}
