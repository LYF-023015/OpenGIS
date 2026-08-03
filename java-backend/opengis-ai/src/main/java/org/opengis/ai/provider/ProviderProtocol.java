package org.opengis.ai.provider;

public enum ProviderProtocol {
  OPENAI,
  ANTHROPIC;

  public static ProviderProtocol parse(String value) {
    return "anthropic".equalsIgnoreCase(value) ? ANTHROPIC : OPENAI;
  }
}
