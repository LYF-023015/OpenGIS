/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.model;

import java.nio.file.Path;
import java.util.List;

/** Stable metadata contract shared by vector and raster loaders. */
public record GisMetadata(
    Path path,
    String name,
    String formatType,
    String formatName,
    long fileSizeBytes,
    String crs,
    double[] bounds,
    Long featureCount,
    List<Field> fields,
    Integer width,
    Integer height,
    Integer bandCount,
    double[] resolution) {
  public GisMetadata {
    path = path.toAbsolutePath().normalize();
    fields = fields == null ? List.of() : List.copyOf(fields);
    bounds = bounds == null ? null : bounds.clone();
    resolution = resolution == null ? null : resolution.clone();
  }

  public record Field(String name, String type) {}
}
