/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.model;

/** A directed connection between two nodes and optional named ports. */
public record WorkflowEdge(
    String id,
    String source,
    String sourceHandle,
    String target,
    String targetHandle,
    String label) {}
