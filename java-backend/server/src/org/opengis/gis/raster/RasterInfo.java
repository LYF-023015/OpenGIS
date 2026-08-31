/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.raster;

import java.nio.file.Path;
import java.util.List;

/** Raster metadata compatible with the existing renderer contract. */
public record RasterInfo(
    Path path,
    String name,
    String driver,
    int width,
    int height,
    int bandCount,
    String crs,
    double[] bbox,
    double[] sourceBbox,
    Double nodata,
    String dtype,
    List<BandStats> bandStats,
    double[] resolution,
    long fileSizeBytes) {
  public RasterInfo {
    path = path.toAbsolutePath().normalize();
    bbox = bbox == null ? null : bbox.clone();
    sourceBbox = sourceBbox == null ? null : sourceBbox.clone();
    resolution = resolution == null ? null : resolution.clone();
    bandStats = List.copyOf(bandStats);
  }

  public record BandStats(int band, Double min, Double max, Double mean, Double p2, Double p98) {}
}
