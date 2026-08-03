package org.opengis.tool.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolDefinition;

/** Deterministic, duplicate-safe registry assembled by the server composition root. */
public final class ToolRegistry {
  private final Map<String, OpenGisTool> tools = new LinkedHashMap<>();

  public ToolRegistry register(OpenGisTool tool) {
    String name = tool.definition().name();
    if (tools.putIfAbsent(name, tool) != null) {
      throw new IllegalArgumentException("Duplicate tool registration: " + name);
    }
    return this;
  }

  public ToolRegistry registerAll(List<? extends OpenGisTool> values) {
    values.forEach(this::register);
    return this;
  }

  public Optional<OpenGisTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public List<ToolDefinition> definitions() {
    List<ToolDefinition> values = new ArrayList<>();
    tools.values().forEach(tool -> values.add(tool.definition()));
    values.sort(Comparator.comparing(ToolDefinition::name));
    return List.copyOf(values);
  }

  public int size() {
    return tools.size();
  }
}
