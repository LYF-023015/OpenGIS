package org.opengis.tool.permission;

public record PermissionDecision(PermissionAction action, String reason, String rule) {}
