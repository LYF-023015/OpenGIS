/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.model;

/** Roles understood by the provider-neutral conversation model. */
public enum LlmRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL
}
