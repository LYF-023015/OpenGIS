/** 文件职责：code 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.code.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.opengis.automation.code.dependency.DependencyResolver;

/** Resolves the compiler and child-JVM classpaths for development and packaged runtimes. */
final class JavaRuntimeClasspath {
  private JavaRuntimeClasspath() {}

  static Resolved resolve(Path temporary, List<DependencyResolver.ResolvedDependency> dependencies)
      throws IOException {
    List<Path> current =
        Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(Path::of)
            .map(path -> path.toAbsolutePath().normalize())
            .filter(Files::exists)
            .toList();
    List<Path> dependencyJars =
        dependencies.stream().map(DependencyResolver.ResolvedDependency::jar).toList();

    if (current.size() == 1
        && Files.isRegularFile(current.getFirst())
        && isSpringBootJar(current.getFirst())) {
      Path sdk = extractScriptSdk(current.getFirst(), temporary);
      List<Path> compiler = new ArrayList<>();
      compiler.add(sdk);
      compiler.addAll(dependencyJars);
      return new Resolved(compiler, dependencyJars, current.getFirst());
    }

    List<Path> compiler = new ArrayList<>(current);
    compiler.addAll(dependencyJars);
    return new Resolved(compiler, compiler, null);
  }

  private static boolean isSpringBootJar(Path path) {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      return zip.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class")
          != null;
    } catch (IOException exception) {
      return false;
    }
  }

  private static Path extractScriptSdk(Path bootJar, Path temporary) throws IOException {
    try (ZipFile zip = new ZipFile(bootJar.toFile())) {
      var entry =
          zip.stream()
              .filter(value -> value.getName().matches("BOOT-INF/lib/opengis-script-sdk-.*\\.jar"))
              .findFirst()
              .orElseThrow(() -> new IOException("Bundled Script SDK jar is missing"));
      Path target = temporary.resolve("opengis-script-sdk.jar");
      Files.copy(zip.getInputStream(entry), target);
      return target;
    }
  }

  record Resolved(List<Path> compilerClasspath, List<Path> runtimeClasspath, Path bootJar) {}
}
