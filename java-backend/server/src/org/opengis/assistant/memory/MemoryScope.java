/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory;

/** Lifetime and visibility boundary for one memory record. */
public enum MemoryScope {
  GLOBAL,
  WORKSPACE,
  CONVERSATION,
  RUN
}
