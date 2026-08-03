package org.opengis.gis.operation;

import java.nio.file.Path;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.JsonNode;

/** Trusted built-in GIS operation compiled into the application distribution. */
public interface BuiltinOperation {
  String id();

  JsonNode manifest();

  JsonNode run(Path workspace, JsonNode parameters, CancellationToken cancellation);
}
