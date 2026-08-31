/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.permission;

public record PermissionDecision(PermissionAction action, String reason, String rule) {}
