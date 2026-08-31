/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.raster;

/** One registered source with monotonic render-style revision. */
public record RasterRegistration(
    String rasterId, RasterInfo info, RasterStyle style, long styleRevision) {}
