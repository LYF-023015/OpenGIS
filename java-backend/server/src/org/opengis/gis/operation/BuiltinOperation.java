/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.operation;

import java.nio.file.Path;
import org.opengis.core.concurrent.CancellationSignal;
import tools.jackson.databind.JsonNode;

/** Trusted built-in GIS operation compiled into the application distribution. */
public interface BuiltinOperation {
  String id();

  JsonNode manifest();

  JsonNode run(Path workspace, JsonNode parameters, CancellationSignal cancellation);
}
