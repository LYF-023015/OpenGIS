/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.api;

/** Coarse risk used after persisted/profile rules and before the default permission. */
public enum ToolRisk {
  READ,
  WRITE,
  PROCESS,
  NETWORK,
  UI,
  DESTRUCTIVE
}
