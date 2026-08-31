/** 文件职责：framework 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.core.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable typed service context shared with mounted plugins by the host application. */
public final class PluginContext {
  private final Map<Class<?>, Object> services;

  private PluginContext(Map<Class<?>, Object> services) {
    this.services = Map.copyOf(services);
  }

  public static Builder builder() {
    return new Builder();
  }

  public <T> T require(Class<T> type) {
    return find(type)
        .orElseThrow(
            () ->
                new PluginLifecycleException(
                    "Required plugin service is not available: " + type.getName()));
  }

  public <T> Optional<T> find(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(services.get(type)).map(type::cast);
  }

  public static final class Builder {
    private final Map<Class<?>, Object> services = new LinkedHashMap<>();

    private Builder() {}

    public <T> Builder service(Class<T> type, T service) {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(service, "service");
      if (!type.isInstance(service)) {
        throw new IllegalArgumentException(
            "Service " + service.getClass().getName() + " does not implement " + type.getName());
      }
      if (services.putIfAbsent(type, service) != null) {
        throw new IllegalArgumentException("Duplicate plugin service: " + type.getName());
      }
      return this;
    }

    public PluginContext build() {
      return new PluginContext(services);
    }
  }
}
