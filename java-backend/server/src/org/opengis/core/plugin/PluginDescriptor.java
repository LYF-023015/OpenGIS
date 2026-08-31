/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.plugin;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Stable identity and dependency metadata for one OpenGIS capability plugin. */
public record PluginDescriptor(String id, String version, Set<String> requires) {
  private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

  public PluginDescriptor {
    id = Objects.requireNonNull(id, "id").strip();
    version = Objects.requireNonNull(version, "version").strip();
    requires = requires == null ? Set.of() : Set.copyOf(requires);
    if (!VALID_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Invalid plugin id: " + id);
    }
    if (version.isBlank()) {
      throw new IllegalArgumentException("Plugin version must not be blank: " + id);
    }
    if (requires.contains(id)) {
      throw new IllegalArgumentException("Plugin cannot require itself: " + id);
    }
    for (String dependency : requires) {
      if (dependency == null || !VALID_ID.matcher(dependency).matches()) {
        throw new IllegalArgumentException(
            "Invalid dependency id for plugin " + id + ": " + dependency);
      }
    }
  }

  public static PluginDescriptor of(String id) {
    return new PluginDescriptor(id, "1.0.0", Set.of());
  }
}
