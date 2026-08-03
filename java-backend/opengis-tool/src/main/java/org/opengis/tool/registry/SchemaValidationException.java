package org.opengis.tool.registry;

import java.util.List;

public class SchemaValidationException extends RuntimeException {
  private final List<String> violations;

  public SchemaValidationException(List<String> violations) {
    super("Invalid tool arguments: " + String.join("; ", violations));
    this.violations = List.copyOf(violations);
  }

  public List<String> violations() {
    return violations;
  }
}
