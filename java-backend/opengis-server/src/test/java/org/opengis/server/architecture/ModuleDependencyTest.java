package org.opengis.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleDependencyTest {
  private static final Set<String> MODULES =
      Set.of(
          "common",
          "framework",
          "platform",
          "ai",
          "knowledge",
          "tool",
          "agent",
          "workflow",
          "gis",
          "worker",
          "server");

  private static final Map<String, Set<String>> ALLOWED =
      Map.ofEntries(
          Map.entry("common", Set.of("common")),
          Map.entry("framework", Set.of("common", "framework")),
          Map.entry("platform", Set.of("common", "framework", "platform")),
          Map.entry("ai", Set.of("common", "framework", "ai")),
          Map.entry("knowledge", Set.of("common", "framework", "platform", "knowledge")),
          Map.entry("tool", Set.of("common", "framework", "platform", "tool")),
          Map.entry(
              "agent",
              Set.of("common", "framework", "platform", "ai", "knowledge", "tool", "agent")),
          Map.entry(
              "workflow", Set.of("common", "framework", "platform", "agent", "tool", "workflow")),
          Map.entry("gis", Set.of("common", "framework", "platform", "tool", "gis")),
          Map.entry("worker", Set.of("common", "framework", "platform", "tool", "worker")),
          Map.entry("server", MODULES));

  @Test
  void modulesOnlyDependOnTheirDeclaredInternalLayers() {
    JavaClasses classes = new ClassFileImporter().importPackages("org.opengis");
    for (String owner : MODULES) {
      Set<String> forbidden =
          MODULES.stream()
              .filter(module -> !ALLOWED.get(owner).contains(module))
              .collect(java.util.stream.Collectors.toSet());
      if (forbidden.isEmpty()) {
        continue;
      }
      String[] forbiddenPackages =
          forbidden.stream().map(module -> "org.opengis." + module + "..").toArray(String[]::new);
      noClasses()
          .that()
          .resideInAPackage("org.opengis." + owner + "..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(forbiddenPackages)
          .because(owner + " may only depend on " + ALLOWED.get(owner))
          .check(classes);
    }
  }
}
