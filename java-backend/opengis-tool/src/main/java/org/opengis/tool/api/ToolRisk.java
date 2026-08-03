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
