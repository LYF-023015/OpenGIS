/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.model;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Formats exposed by the Java GIS computation plane. */
public enum GisFormat {
  GEOJSON("vector", "GeoJSON", "pure_java"),
  CSV("vector", "CSV", "pure_java"),
  SHAPEFILE("vector", "Shapefile", "pure_java"),
  GEOPACKAGE("vector", "GeoPackage", "pure_java"),
  KML("vector", "KML", "pure_java"),
  GEOTIFF("raster", "GeoTIFF", "pure_java"),
  NETCDF("raster", "NetCDF", "pure_java"),
  HDF5("raster", "HDF5/NetCDF-4", "pure_java"),
  JPEG2000("raster", "JPEG2000", "pure_java"),
  ASCII_GRID("raster", "ASCII Grid", "pure_java");

  private static final Map<String, GisFormat> EXTENSIONS =
      Map.ofEntries(
          Map.entry(".geojson", GEOJSON),
          Map.entry(".json", GEOJSON),
          Map.entry(".csv", CSV),
          Map.entry(".shp", SHAPEFILE),
          Map.entry(".gpkg", GEOPACKAGE),
          Map.entry(".kml", KML),
          Map.entry(".tif", GEOTIFF),
          Map.entry(".tiff", GEOTIFF),
          Map.entry(".nc", NETCDF),
          Map.entry(".nc4", HDF5),
          Map.entry(".hdf5", HDF5),
          Map.entry(".h5", HDF5),
          Map.entry(".jp2", JPEG2000),
          Map.entry(".j2k", JPEG2000),
          Map.entry(".j2c", JPEG2000),
          Map.entry(".jpc", JPEG2000),
          Map.entry(".asc", ASCII_GRID));

  private final String kind;
  private final String displayName;
  private final String support;

  GisFormat(String kind, String displayName, String support) {
    this.kind = kind;
    this.displayName = displayName;
    this.support = support;
  }

  public String kind() {
    return kind;
  }

  public String displayName() {
    return displayName;
  }

  public String support() {
    return support;
  }

  public static Optional<GisFormat> detect(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? Optional.empty() : Optional.ofNullable(EXTENSIONS.get(name.substring(dot)));
  }
}
