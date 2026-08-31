/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.provider;

public enum ProviderProtocol {
  OPENAI,
  ANTHROPIC;

  public static ProviderProtocol parse(String value) {
    return "anthropic".equalsIgnoreCase(value) ? ANTHROPIC : OPENAI;
  }
}
