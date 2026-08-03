package org.opengis.workflow.model;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Versioned, data-only workflow definition. Executable code is referenced, never embedded. */
public record WorkflowDocument(
    int schemaVersion,
    String id,
    String name,
    String description,
    String createdAt,
    String updatedAt,
    List<WorkflowNode> nodes,
    List<WorkflowEdge> edges,
    JsonNode viewport,
    Map<String, JsonNode> metadata) {
  public static final int CURRENT_SCHEMA_VERSION = 2;

  public WorkflowDocument {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    edges = edges == null ? List.of() : List.copyOf(edges);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
