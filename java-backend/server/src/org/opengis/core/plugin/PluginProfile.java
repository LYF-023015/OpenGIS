/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.plugin;

import java.util.Objects;
import java.util.Set;

/** Named root-plugin selection; transitive dependencies are included automatically. */
public record PluginProfile(String id, Set<String> enabledPlugins) {
  public PluginProfile {
    id = Objects.requireNonNull(id, "id").strip();
    enabledPlugins = enabledPlugins == null ? Set.of() : Set.copyOf(enabledPlugins);
    if (id.isBlank()) {
      throw new IllegalArgumentException("Plugin profile id must not be blank");
    }
  }

  public static PluginProfile all() {
    return new PluginProfile("all", Set.of());
  }
}
