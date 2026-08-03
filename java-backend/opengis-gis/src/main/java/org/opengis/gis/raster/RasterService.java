package org.opengis.gis.raster;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.coverage.io.netcdf.NetCDFReader;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.model.GisFormat;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.CancellationToken;

/** Pure-Java raster metadata, registration, style revision and on-demand XYZ PNG rendering. */
public final class RasterService {
  public static final int TILE_SIZE = 256;
  private static final int MAX_ZOOM = 24;
  private static final int MAX_REGISTRATIONS = 64;
  private static final int MAX_STAT_SAMPLES = 250_000;

  private final CrsService crs;
  private final Map<String, RasterRegistration> registrations = new ConcurrentHashMap<>();
  private final RasterTileCache cache = new RasterTileCache(64L * 1024 * 1024);

  public RasterService(CrsService crs) {
    this.crs = crs;
  }

  public RasterInfo inspect(Path path, CancellationToken cancellation) {
    cancellation.throwIfCancelled();
    CoverageHandle handle = null;
    try {
      handle = openCoverage(path);
      GridCoverage2D coverage = handle.coverage();
      RenderedImage image = coverage.getRenderedImage();
      ReferencedEnvelope envelope = coverage.getEnvelope2D();
      CoordinateReferenceSystem coverageCrs = coverage.getCoordinateReferenceSystem2D();
      String sourceCrs = coverageCrs == null ? null : crs.identifier(coverageCrs);
      double[] sourceBounds =
          new double[] {
            envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY()
          };
      double[] wgs84 =
          sourceCrs == null || sourceCrs.isBlank()
              ? null
              : transformEnvelope(sourceBounds, sourceCrs, "EPSG:4326");
      List<RasterInfo.BandStats> stats = sampleStats(image, cancellation);
      return new RasterInfo(
          path,
          path.getFileName().toString(),
          rasterFormat(path).displayName(),
          image.getWidth(),
          image.getHeight(),
          image.getSampleModel().getNumBands(),
          sourceCrs,
          wgs84,
          sourceBounds,
          noData(coverage),
          dataType(image.getSampleModel().getDataType()),
          stats,
          new double[] {
            envelope.getWidth() / image.getWidth(), envelope.getHeight() / image.getHeight()
          },
          Files.size(path));
    } catch (ToolException exception) {
      if (rasterFormat(path) == GisFormat.JPEG2000) return inspectImage(path, cancellation);
      throw exception;
    } catch (Exception exception) {
      if (rasterFormat(path) == GisFormat.JPEG2000) return inspectImage(path, cancellation);
      throw new ToolException(
          "raster_read_failed", "Cannot inspect " + rasterFormat(path).displayName(), exception);
    } finally {
      if (handle != null) handle.close();
    }
  }

  public RasterRegistration register(Path path, RasterStyle style, CancellationToken cancellation) {
    if (registrations.size() >= MAX_REGISTRATIONS) {
      String first = registrations.keySet().stream().sorted().findFirst().orElse(null);
      if (first != null) unregister(first);
    }
    RasterInfo info = inspect(path, cancellation);
    RasterStyle normalized = style == null ? RasterStyle.defaults() : style;
    if (normalized.band() > info.bandCount()) {
      throw new ToolException("invalid_raster_band", "Raster band is outside source band count");
    }
    String id = "rst_" + UUID.randomUUID().toString().replace("-", "");
    RasterRegistration registration = new RasterRegistration(id, info, normalized, 0);
    registrations.put(id, registration);
    return registration;
  }

  public RasterRegistration get(String rasterId) {
    RasterRegistration value = registrations.get(rasterId);
    if (value == null)
      throw new ToolException("raster_not_found", "Raster is not registered: " + rasterId);
    return value;
  }

  public RasterRegistration updateStyle(String rasterId, Map<String, ?> changes) {
    try {
      return registrations.compute(
          rasterId,
          (id, current) -> {
            if (current == null)
              throw new ToolException("raster_not_found", "Raster is not registered: " + id);
            RasterStyle style =
                RasterStyle.merge(current.style(), changes == null ? Map.of() : changes);
            if (style.band() > current.info().bandCount()) {
              throw new ToolException(
                  "invalid_raster_band", "Raster band is outside source band count");
            }
            cache.evictRaster(id);
            return new RasterRegistration(id, current.info(), style, current.styleRevision() + 1);
          });
    } catch (IllegalArgumentException exception) {
      throw new ToolException("invalid_raster_style", exception.getMessage(), exception);
    }
  }

  public void unregister(String rasterId) {
    registrations.remove(rasterId);
    cache.evictRaster(rasterId);
  }

  public byte[] renderTile(String rasterId, int z, int x, int y) {
    validateTile(z, x, y);
    RasterRegistration registration = get(rasterId);
    RasterTileCache.Key key =
        new RasterTileCache.Key(rasterId, registration.styleRevision(), z, x, y);
    byte[] cached = cache.get(key);
    if (cached != null) return cached;
    byte[] rendered = render(registration, z, x, y);
    cache.put(key, rendered);
    return rendered;
  }

  public Map<String, Object> cacheStats() {
    RasterTileCache.Stats stats = cache.stats();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("entries", stats.entries());
    result.put("bytes", stats.bytes());
    result.put("max_bytes", stats.maxBytes());
    result.put("registrations", registrations.size());
    return result;
  }

  private byte[] render(RasterRegistration registration, int z, int x, int y) {
    CoverageHandle handle = null;
    try {
      handle = openCoverage(registration.info().path());
      GridCoverage2D coverage = handle.coverage();
      CoordinateReferenceSystem source = coverage.getCoordinateReferenceSystem2D();
      if (source == null) {
        throw new ToolException(
            "raster_crs_missing", "Raster tiles require embedded or sidecar georeferencing");
      }
      MathTransform transform = CRS.findMathTransform(crs.decode("EPSG:4326"), source, true);
      double[] tileBounds = xyzBounds(z, x, y);
      BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
      RasterStyle style = registration.style();
      RasterInfo.BandStats stats = registration.info().bandStats().get(style.band() - 1);
      double min = style.min() == null ? fallback(stats.p2(), stats.min(), 0) : style.min();
      double max = style.max() == null ? fallback(stats.p98(), stats.max(), min + 1) : style.max();
      double[] sample = new double[registration.info().bandCount()];
      Position2D wgs = new Position2D(crs.decode("EPSG:4326"));
      Position2D src = new Position2D(source);
      for (int py = 0; py < TILE_SIZE; py++) {
        double lat = tileBounds[3] - (py + 0.5) / TILE_SIZE * (tileBounds[3] - tileBounds[1]);
        for (int px = 0; px < TILE_SIZE; px++) {
          double lon = tileBounds[0] + (px + 0.5) / TILE_SIZE * (tileBounds[2] - tileBounds[0]);
          wgs.setLocation(lon, lat);
          try {
            transform.transform(wgs, src);
            coverage.evaluate((org.geotools.api.geometry.Position) src, sample);
            double value = sample[style.band() - 1];
            if (Double.isFinite(value)) image.setRGB(px, py, color(value, min, max, style));
          } catch (RuntimeException ignored) {
            // A tile can legitimately include pixels outside the coverage.
          }
        }
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(image, "png", output);
      return output.toByteArray();
    } catch (Exception exception) {
      throw new ToolException("raster_tile_failed", "Cannot render raster tile", exception);
    } finally {
      if (handle != null) handle.close();
    }
  }

  private double[] transformEnvelope(double[] bounds, String source, String target) {
    if (source == null || source.equalsIgnoreCase(target)) return bounds.clone();
    try {
      MathTransform transform = CRS.findMathTransform(crs.decode(source), crs.decode(target), true);
      double[] points = {
        bounds[0], bounds[1], bounds[0], bounds[3], bounds[2], bounds[1], bounds[2], bounds[3]
      };
      transform.transform(points, 0, points, 0, 4);
      double minX = Math.min(Math.min(points[0], points[2]), Math.min(points[4], points[6]));
      double minY = Math.min(Math.min(points[1], points[3]), Math.min(points[5], points[7]));
      double maxX = Math.max(Math.max(points[0], points[2]), Math.max(points[4], points[6]));
      double maxY = Math.max(Math.max(points[1], points[3]), Math.max(points[5], points[7]));
      return new double[] {minX, minY, maxX, maxY};
    } catch (Exception exception) {
      throw new ToolException("raster_crs_failed", "Cannot transform raster bounds", exception);
    }
  }

  private static List<RasterInfo.BandStats> sampleStats(
      RenderedImage image, CancellationToken cancellation) {
    int stride =
        Math.max(
            1,
            (int)
                Math.ceil(
                    Math.sqrt((double) image.getWidth() * image.getHeight() / MAX_STAT_SAMPLES)));
    List<RasterInfo.BandStats> result = new ArrayList<>();
    for (int band = 0; band < image.getSampleModel().getNumBands(); band++) {
      List<Double> values = new ArrayList<>();
      double sum = 0;
      for (int tileY = image.getMinTileY();
          tileY < image.getMinTileY() + image.getNumYTiles();
          tileY++) {
        cancellation.throwIfCancelled();
        for (int tileX = image.getMinTileX();
            tileX < image.getMinTileX() + image.getNumXTiles();
            tileX++) {
          Raster tile = image.getTile(tileX, tileY);
          for (int y = tile.getMinY(); y < tile.getMinY() + tile.getHeight(); y += stride) {
            for (int x = tile.getMinX(); x < tile.getMinX() + tile.getWidth(); x += stride) {
              double value = tile.getSampleDouble(x, y, band);
              if (Double.isFinite(value)) {
                values.add(value);
                sum += value;
              }
            }
          }
        }
      }
      values.sort(Comparator.naturalOrder());
      if (values.isEmpty()) {
        result.add(new RasterInfo.BandStats(band + 1, null, null, null, null, null));
      } else {
        result.add(
            new RasterInfo.BandStats(
                band + 1,
                values.getFirst(),
                values.getLast(),
                sum / values.size(),
                percentile(values, 0.02),
                percentile(values, 0.98)));
      }
    }
    return List.copyOf(result);
  }

  private static int color(double value, double min, double max, RasterStyle style) {
    double normalized = Math.max(0, Math.min(1, (value - min) / Math.max(1e-12, max - min)));
    if (style.reverse()) normalized = 1 - normalized;
    if (!style.stops().isEmpty()) {
      return customColor(value, normalized, style);
    }
    int[] rgb = ramp(normalized, style.ramp());
    int alpha = (int) Math.round(style.opacity() * 255);
    return (alpha << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
  }

  private static int[] ramp(double value, String name) {
    if ("gray".equals(name) || "grayscale".equals(name)) {
      int gray = (int) Math.round(value * 255);
      return new int[] {gray, gray, gray};
    }
    int[][] colors =
        switch (name) {
          case "terrain" ->
              new int[][] {{20, 90, 40}, {120, 160, 70}, {180, 130, 70}, {245, 245, 240}};
          case "magma" -> new int[][] {{0, 0, 4}, {85, 15, 109}, {187, 55, 84}, {252, 253, 191}};
          case "plasma" ->
              new int[][] {{13, 8, 135}, {156, 23, 158}, {237, 121, 83}, {240, 249, 33}};
          default -> new int[][] {{68, 1, 84}, {49, 104, 142}, {53, 183, 121}, {253, 231, 37}};
        };
    double scaled = value * (colors.length - 1);
    int index = Math.min(colors.length - 2, (int) Math.floor(scaled));
    double fraction = scaled - index;
    return new int[] {
      interpolate(colors[index][0], colors[index + 1][0], fraction),
      interpolate(colors[index][1], colors[index + 1][1], fraction),
      interpolate(colors[index][2], colors[index + 1][2], fraction)
    };
  }

  private static int interpolate(int left, int right, double fraction) {
    return (int) Math.round(left + (right - left) * fraction);
  }

  private static double percentile(List<Double> sorted, double fraction) {
    int index = (int) Math.round((sorted.size() - 1) * fraction);
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private static double fallback(Double preferred, Double secondary, double fallback) {
    if (preferred != null && Double.isFinite(preferred)) return preferred;
    if (secondary != null && Double.isFinite(secondary)) return secondary;
    return fallback;
  }

  private static int customColor(double source, double normalized, RasterStyle style) {
    double value = "source".equals(style.stopsUnit()) ? source : normalized;
    List<RasterStyle.ColorStop> stops = style.stops();
    RasterStyle.ColorStop left = stops.getFirst();
    RasterStyle.ColorStop right = stops.getLast();
    for (int index = 1; index < stops.size(); index++) {
      if (value <= stops.get(index).value()) {
        left = stops.get(index - 1);
        right = stops.get(index);
        break;
      }
    }
    double fraction =
        left == right
            ? 0
            : Math.max(
                0,
                Math.min(
                    1, (value - left.value()) / Math.max(1e-12, right.value() - left.value())));
    int leftRgb = Integer.parseInt(left.color().substring(1), 16);
    int rightRgb = Integer.parseInt(right.color().substring(1), 16);
    int red = interpolate((leftRgb >> 16) & 255, (rightRgb >> 16) & 255, fraction);
    int green = interpolate((leftRgb >> 8) & 255, (rightRgb >> 8) & 255, fraction);
    int blue = interpolate(leftRgb & 255, rightRgb & 255, fraction);
    double stopOpacity = left.opacity() + (right.opacity() - left.opacity()) * fraction;
    int alpha = (int) Math.round(style.opacity() * stopOpacity * 255);
    return (alpha << 24) | (red << 16) | (green << 8) | blue;
  }

  private static Double noData(GridCoverage2D coverage) {
    try {
      double[] values = coverage.getSampleDimension(0).getNoDataValues();
      return values == null || values.length == 0 ? null : values[0];
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String dataType(int type) {
    return switch (type) {
      case java.awt.image.DataBuffer.TYPE_BYTE -> "uint8";
      case java.awt.image.DataBuffer.TYPE_USHORT -> "uint16";
      case java.awt.image.DataBuffer.TYPE_SHORT -> "int16";
      case java.awt.image.DataBuffer.TYPE_INT -> "int32";
      case java.awt.image.DataBuffer.TYPE_FLOAT -> "float32";
      case java.awt.image.DataBuffer.TYPE_DOUBLE -> "float64";
      default -> "unknown";
    };
  }

  private static void validateTile(int z, int x, int y) {
    if (z < 0 || z > MAX_ZOOM) throw new ToolException("invalid_tile", "Zoom must be 0..24");
    int width = 1 << Math.min(z, 30);
    if (x < 0 || y < 0 || x >= width || y >= width) {
      throw new ToolException("invalid_tile", "XYZ coordinate is outside zoom grid");
    }
  }

  private static double[] xyzBounds(int z, int x, int y) {
    double scale = Math.scalb(1.0, z);
    double west = x / scale * 360 - 180;
    double east = (x + 1) / scale * 360 - 180;
    double north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / scale))));
    double south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 1) / scale))));
    return new double[] {west, south, east, north};
  }

  private CoverageHandle openCoverage(Path path) {
    try {
      if (rasterFormat(path) == GisFormat.GEOTIFF) {
        ImageInputStream input = ImageIO.createImageInputStream(path.toFile());
        GeoTiffReader reader = new GeoTiffReader(input);
        GridCoverage2D coverage = reader.read();
        if (coverage == null) {
          reader.dispose();
          input.close();
          throw new ToolException("raster_empty", "Raster reader returned no coverage");
        }
        return new CoverageHandle(reader, coverage, input);
      }
      if (rasterFormat(path) == GisFormat.NETCDF || rasterFormat(path) == GisFormat.HDF5) {
        AbstractGridCoverage2DReader reader = new NetCDFReader(path.toFile(), null);
        GridCoverage2D coverage = reader.read();
        if (coverage == null) {
          reader.dispose();
          throw new ToolException("raster_empty", "NetCDF/HDF5 reader returned no coverage");
        }
        return new CoverageHandle(reader, coverage, null);
      }
      GridFormatFinder.scanForPlugins();
      var format = GridFormatFinder.findFormat(path.toFile());
      AbstractGridCoverage2DReader reader = format == null ? null : format.getReader(path.toFile());
      if (reader == null) {
        throw new ToolException(
            "raster_reader_unavailable",
            "No bundled Java raster reader accepted " + path.getFileName());
      }
      GridCoverage2D coverage = reader.read();
      if (coverage == null) {
        reader.dispose();
        throw new ToolException("raster_empty", "Raster reader returned no coverage");
      }
      return new CoverageHandle(reader, coverage, null);
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("raster_read_failed", "Cannot open raster coverage", exception);
    }
  }

  private RasterInfo inspectImage(Path path, CancellationToken cancellation) {
    try {
      cancellation.throwIfCancelled();
      BufferedImage image = ImageIO.read(path.toFile());
      if (image == null) {
        throw new ToolException(
            "raster_reader_unavailable", "Bundled JPEG2000 reader rejected the image");
      }
      return new RasterInfo(
          path,
          path.getFileName().toString(),
          "JPEG2000",
          image.getWidth(),
          image.getHeight(),
          image.getSampleModel().getNumBands(),
          null,
          null,
          null,
          null,
          dataType(image.getSampleModel().getDataType()),
          sampleStats(image, cancellation),
          null,
          Files.size(path));
    } catch (ToolException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ToolException("raster_read_failed", "Cannot inspect JPEG2000", exception);
    }
  }

  private static GisFormat rasterFormat(Path path) {
    return GisFormat.detect(path)
        .filter(value -> "raster".equals(value.kind()))
        .orElseThrow(() -> new ToolException("unsupported_gis_format", "Not a raster format"));
  }

  private record CoverageHandle(
      AbstractGridCoverage2DReader reader, GridCoverage2D coverage, AutoCloseable resource)
      implements AutoCloseable {
    @Override
    public void close() {
      coverage.dispose(true);
      reader.dispose();
      if (resource != null) {
        try {
          resource.close();
        } catch (Exception ignored) {
          // Reader cleanup is best effort after the coverage has already been disposed.
        }
      }
    }
  }
}
