package org.opengis.tool.registry;

import java.util.List;

public class SchemaValidationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient List<String> violations;

  public SchemaValidationException(List<String> violations) {
    super("Invalid tool arguments: " + String.join("; ", violations));
    this.violations = List.copyOf(violations);
  }

  public List<String> violations() {
    return violations;
  }
}
