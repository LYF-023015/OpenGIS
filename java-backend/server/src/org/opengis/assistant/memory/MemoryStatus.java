/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory;

/** Lifecycle state used by consolidation without destructively deleting provenance. */
public enum MemoryStatus {
  ACTIVE,
  ARCHIVED,
  SUPERSEDED
}
