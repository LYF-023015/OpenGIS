package org.opengis.gis.raster;

/** One registered source with monotonic render-style revision. */
public record RasterRegistration(
    String rasterId, RasterInfo info, RasterStyle style, long styleRevision) {}
