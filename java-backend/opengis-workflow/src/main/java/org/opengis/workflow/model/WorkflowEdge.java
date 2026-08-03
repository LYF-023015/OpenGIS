package org.opengis.workflow.model;

/** A directed connection between two nodes and optional named ports. */
public record WorkflowEdge(
    String id,
    String source,
    String sourceHandle,
    String target,
    String targetHandle,
    String label) {}
