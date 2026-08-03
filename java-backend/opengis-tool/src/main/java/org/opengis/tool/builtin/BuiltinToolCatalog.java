package org.opengis.tool.builtin;

import java.util.ArrayList;
import java.util.List;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.registry.ToolRegistry;
import tools.jackson.databind.ObjectMapper;

/** Ordered Phase 4 composition: read -> write -> process/network -> UI -> knowledge -> GIS. */
public final class BuiltinToolCatalog {
  private BuiltinToolCatalog() {}

  public static List<OpenGisTool> create(ObjectMapper mapper) {
    List<OpenGisTool> tools = new ArrayList<>();
    tools.addAll(FileTools.create(mapper));
    tools.add(new ShellTool(mapper));
    tools.add(new WebFetchTool(mapper));
    tools.addAll(UiCommandTools.create(mapper));
    tools.addAll(KnowledgeTools.create(mapper));
    tools.add(new CsvToGeoJsonTool(mapper));
    return List.copyOf(tools);
  }

  public static ToolRegistry registry(ObjectMapper mapper) {
    return new ToolRegistry().registerAll(create(mapper));
  }
}
