/** 文件职责：code 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.code.validation;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** JavaParser-based structural and restricted-API validation for user source. */
public final class JavaSourceValidator {
  private static final Set<String> BLOCKED_CONSTRUCTORS =
      Set.of("ProcessBuilder", "URLClassLoader", "Socket", "ServerSocket");
  private static final Set<String> BLOCKED_METHODS =
      Set.of("exec", "exit", "halt", "load", "loadLibrary", "setSecurityManager");
  private static final List<String> BLOCKED_IMPORT_PREFIXES =
      List.of("java.lang.reflect", "sun.", "jdk.internal.");

  public ValidationResult validate(
      String source, String entryClass, Set<String> declaredPermissions) {
    List<Issue> errors = new ArrayList<>();
    List<Issue> warnings = new ArrayList<>();
    if (source == null || source.isBlank()) {
      return new ValidationResult(
          false, List.of(new Issue("empty_source", "Source is empty")), List.of());
    }
    if (source.length() > 1_000_000) {
      errors.add(new Issue("source_too_large", "Java source exceeds 1,000,000 characters"));
    }
    try {
      CompilationUnit unit = StaticJavaParser.parse(source);
      String simpleName = entryClass.substring(entryClass.lastIndexOf('.') + 1);
      boolean entryPresent =
          unit.getTypes().stream().anyMatch(type -> type.getNameAsString().equals(simpleName));
      if (!entryPresent) {
        errors.add(new Issue("entry_class_missing", "Entry class is not declared: " + entryClass));
      }
      for (ImportDeclaration imported : unit.getImports()) {
        String name = imported.getNameAsString();
        if (BLOCKED_IMPORT_PREFIXES.stream().anyMatch(name::startsWith)) {
          errors.add(new Issue("blocked_import", "Blocked import: " + name));
        }
        if (name.startsWith("java.net") && !declaredPermissions.contains("network")) {
          errors.add(
              new Issue("network_permission_required", "java.net requires the network permission"));
        }
        if ((name.startsWith("java.nio.file") || name.startsWith("java.io"))
            && !declaredPermissions.contains("workspace_files")) {
          errors.add(
              new Issue(
                  "filesystem_permission_required",
                  "File APIs require workspace_files permission"));
        }
      }
      unit.findAll(ObjectCreationExpr.class).stream()
          .filter(value -> BLOCKED_CONSTRUCTORS.contains(value.getType().getNameAsString()))
          .forEach(
              value ->
                  errors.add(
                      new Issue(
                          "blocked_constructor",
                          "Blocked constructor: " + value.getTypeAsString())));
      for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
        if (BLOCKED_METHODS.contains(call.getNameAsString())) {
          errors.add(new Issue("blocked_method", "Blocked method call: " + call));
        }
        if ("forName".equals(call.getNameAsString())) {
          errors.add(new Issue("reflection_forbidden", "Class.forName is not allowed"));
        }
      }
      if (unit.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).stream()
          .anyMatch(
              loop ->
                  loop.getCondition().isBooleanLiteralExpr()
                      && loop.getCondition().asBooleanLiteralExpr().getValue())) {
        warnings.add(
            new Issue(
                "unbounded_loop", "A literal while(true) loop should be implemented as a Worker"));
      }
    } catch (ParseProblemException exception) {
      errors.add(new Issue("java_parse_error", exception.getMessage()));
    }
    return new ValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
  }

  public record Issue(String code, String message) {}

  public record ValidationResult(boolean ok, List<Issue> errors, List<Issue> warnings) {}
}
