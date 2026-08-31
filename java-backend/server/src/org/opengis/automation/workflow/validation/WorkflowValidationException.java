/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.validation;

import java.util.List;

/** Raised when a workflow cannot safely be persisted or executed. */
public final class WorkflowValidationException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  private final transient List<String> errors;

  public WorkflowValidationException(List<String> errors) {
    super(String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
