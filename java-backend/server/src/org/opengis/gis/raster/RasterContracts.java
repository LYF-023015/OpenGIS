/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.raster;

import java.util.LinkedHashMap;
import java.util.Map;

/** Python/frontend-compatible snake-case projection of raster domain records. */
public final class RasterContracts {
  private RasterContracts() {}

  public static Map<String, Object> registration(RasterRegistration registration) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("raster_id", registration.rasterId());
    result.put("path", registration.info().path().toString());
    result.put("info", info(registration.info()));
    result.put("style", registration.style());
    result.put("style_revision", registration.styleRevision());
    return result;
  }

  public static Map<String, Object> info(RasterInfo info) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path", info.path().toString());
    result.put("name", info.name());
    result.put("driver", info.driver());
    result.put("width", info.width());
    result.put("height", info.height());
    result.put("band_count", info.bandCount());
    result.put("crs", info.crs());
    result.put("bbox", info.bbox());
    result.put("source_bbox", info.sourceBbox());
    result.put("nodata", info.nodata());
    result.put("dtype", info.dtype());
    result.put("band_stats", info.bandStats());
    result.put("resolution", info.resolution());
    result.put("file_size_bytes", info.fileSizeBytes());
    return result;
  }
}
