/** 文件职责：agent 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.agent.session;

public final class SessionBusyException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SessionBusyException(String message) {
    super(message);
  }
}
