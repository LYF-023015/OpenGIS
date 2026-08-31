/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.opengis.core.plugin.PluginHandle;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;

/** Deterministic, duplicate-safe registry assembled by the server composition root. */
public final class ToolRegistry {
  private final Map<String, OpenGisTool> tools = new LinkedHashMap<>();

  public synchronized ToolRegistry register(OpenGisTool tool) {
    String name = tool.definition().name();
    if (tools.putIfAbsent(name, tool) != null) {
      throw new IllegalArgumentException("Duplicate tool registration: " + name);
    }
    return this;
  }

  public synchronized ToolRegistry registerAll(List<? extends OpenGisTool> values) {
    values.forEach(this::register);
    return this;
  }

  /** Registers one plugin contribution and returns an idempotent ownership handle. */
  public synchronized PluginHandle contribute(OpenGisTool tool) {
    register(tool);
    String name = tool.definition().name();
    return new PluginHandle() {
      private boolean closed;

      @Override
      public void close() {
        synchronized (ToolRegistry.this) {
          if (!closed) {
            tools.remove(name, tool);
            closed = true;
          }
        }
      }
    };
  }

  /** Registers a group atomically; a duplicate rolls back earlier contributions. */
  public synchronized PluginHandle contributeAll(List<? extends OpenGisTool> values) {
    List<PluginHandle> handles = new ArrayList<>();
    try {
      values.forEach(tool -> handles.add(contribute(tool)));
      return PluginHandle.combine(handles);
    } catch (RuntimeException exception) {
      PluginHandle.combine(handles).close();
      throw exception;
    }
  }

  public synchronized Optional<OpenGisTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public synchronized List<ToolDefinition> definitions() {
    List<ToolDefinition> values = new ArrayList<>();
    tools.values().forEach(tool -> values.add(tool.definition()));
    values.sort(Comparator.comparing(ToolDefinition::name));
    return List.copyOf(values);
  }

  public synchronized int size() {
    return tools.size();
  }
}
