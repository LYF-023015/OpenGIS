/** 文件职责：code 后端领域：承载该领域的核心业务流程。 */
package org.opengis.automation.code.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles one reviewed source unit with the JDK compiler into a run-scoped directory. */
public final class JavaCompilationService {
  public CompilationResult compile(
      String source, String entryClass, Path outputDirectory, List<Path> classpath) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return new CompilationResult(false, List.of("JDK JavaCompiler is unavailable"), null);
    }
    Path sourceRoot = outputDirectory.resolve("source");
    Path classes = outputDirectory.resolve("classes");
    Path sourcePath = sourceRoot.resolve(entryClass.replace('.', '/') + ".java").normalize();
    try {
      Files.createDirectories(sourcePath.getParent());
      Files.createDirectories(classes);
      Files.writeString(sourcePath, source, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      return new CompilationResult(
          false, List.of("Cannot prepare compiler input: " + exception.getMessage()), null);
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      var units = manager.getJavaFileObjects(sourcePath.toFile());
      List<String> options = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
      if (!classpath.isEmpty()) {
        options.add("-classpath");
        options.add(
            String.join(
                java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
      }
      boolean success =
          Boolean.TRUE.equals(
              compiler.getTask(null, manager, diagnostics, options, null, units).call());
      List<String> messages =
          diagnostics.getDiagnostics().stream().map(JavaCompilationService::message).toList();
      return new CompilationResult(success, messages, success ? classes : null);
    } catch (IOException exception) {
      return new CompilationResult(
          false, List.of("Compiler failed: " + exception.getMessage()), null);
    }
  }

  private static String message(Diagnostic<? extends JavaFileObject> value) {
    return value.getKind() + " line " + value.getLineNumber() + ": " + value.getMessage(null);
  }

  public record CompilationResult(
      boolean success, List<String> diagnostics, Path classesDirectory) {}
}
