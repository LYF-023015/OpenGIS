/** 文件职责：common 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.protocol;

/** Wire-level constants shared by every OpenGIS transport. */
public final class ProtocolVersion {
  public static final String OPENGIS = "3.0";
  public static final String JSON_RPC = "2.0";
  public static final String RPC_PREFIX = "rpc.";
  public static final String CHAT_PREFIX = "chat.";
  public static final String EVENT_PREFIX = "event.";

  private ProtocolVersion() {}
}
