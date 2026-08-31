/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.model;

/** Soft-typed workflow input/output port used for editor hints and explicit output mapping. */
public record WorkflowPort(String name, String label, String type, String description) {}
