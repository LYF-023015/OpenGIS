package org.opengis.workflow.model;

/** Soft-typed workflow input/output port used for editor hints and explicit output mapping. */
public record WorkflowPort(String name, String label, String type, String description) {}
