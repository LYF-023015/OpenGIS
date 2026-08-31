/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.permission;

public enum PermissionAction {
  ALLOW,
  ASK,
  DENY;

  public static PermissionAction parse(String value) {
    return PermissionAction.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
  }
}
