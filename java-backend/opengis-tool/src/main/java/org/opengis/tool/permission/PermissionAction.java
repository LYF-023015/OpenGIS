package org.opengis.tool.permission;

public enum PermissionAction {
  ALLOW,
  ASK,
  DENY;

  public static PermissionAction parse(String value) {
    return PermissionAction.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
  }
}
