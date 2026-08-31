/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleDependencyTest {
  private static final Set<String> MODULES =
      Set.of("core", "assistant", "gis", "automation", "tool", "server");

  private static final Map<String, Set<String>> ALLOWED =
      Map.ofEntries(
          Map.entry("core", Set.of("core")),
          Map.entry("assistant", Set.of("core", "assistant", "tool")),
          Map.entry("automation", Set.of("core", "automation", "tool")),
          Map.entry("gis", Set.of("core", "automation", "gis")),
          Map.entry("tool", Set.of("core", "assistant", "automation", "gis", "tool")),
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
