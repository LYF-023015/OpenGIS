package org.opengis.workflow.validation;

import java.util.List;

/** Raised when a workflow cannot safely be persisted or executed. */
public final class WorkflowValidationException extends IllegalArgumentException {
  private final List<String> errors;

  public WorkflowValidationException(List<String> errors) {
    super(String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
