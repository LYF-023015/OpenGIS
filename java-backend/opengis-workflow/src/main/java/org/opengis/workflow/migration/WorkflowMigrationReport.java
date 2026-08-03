package org.opengis.workflow.migration;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Auditable v1 inspection/conversion result; conversion never silently drops executable behavior.
 */
public record WorkflowMigrationReport(
    String sourceVersion,
    int targetVersion,
    String status,
    List<Issue> issues,
    JsonNode convertedWorkflow) {
  public record Issue(String severity, String code, String nodeId, String message) {}
}
