/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.raster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable raster render style; all values are normalized at the boundary. */
public record RasterStyle(
    int band,
    String ramp,
    Double min,
    Double max,
    double opacity,
    boolean reverse,
    List<ColorStop> stops,
    String stopsUnit) {
  public RasterStyle {
    band = Math.max(1, band);
    ramp = normalizeRamp(ramp);
    opacity = Math.max(0, Math.min(1, opacity));
    if (min != null && max != null && min >= max) {
      throw new IllegalArgumentException("Raster style min must be less than max");
    }
    stops =
        stops == null
            ? List.of()
            : stops.stream().sorted(Comparator.comparingDouble(ColorStop::value)).toList();
    stopsUnit = "source".equalsIgnoreCase(stopsUnit) ? "source" : "normalized";
  }

  public static RasterStyle defaults() {
    return new RasterStyle(1, "viridis", null, null, 1, false, List.of(), "normalized");
  }

  public static RasterStyle merge(RasterStyle current, Map<String, ?> changes) {
    return new RasterStyle(
        integer(changes.get("band"), current.band()),
        string(changes.get("ramp"), current.ramp()),
        decimal(changes.get("min"), current.min()),
        decimal(changes.get("max"), current.max()),
        decimal(changes.get("opacity"), current.opacity()),
        bool(changes.get("reverse"), current.reverse()),
        stops(changes.get("stops"), current.stops()),
        string(changes.get("stopsUnit"), string(changes.get("stops_unit"), current.stopsUnit())));
  }

  private static String normalizeRamp(String value) {
    String ramp = value == null ? "viridis" : value.toLowerCase(Locale.ROOT);
    return switch (ramp) {
      case "viridis", "terrain", "gray", "grayscale", "magma", "plasma" -> ramp;
      default -> "viridis";
    };
  }

  private static int integer(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static double decimal(Object value, double fallback) {
    return value instanceof Number number ? number.doubleValue() : fallback;
  }

  private static Double decimal(Object value, Double fallback) {
    if (value instanceof Number number) {
      return Double.valueOf(number.doubleValue());
    }
    return fallback;
  }

  private static boolean bool(Object value, boolean fallback) {
    return value instanceof Boolean bool ? bool : fallback;
  }

  private static String string(Object value, String fallback) {
    return value instanceof String text && !text.isBlank() ? text : fallback;
  }

  private static List<ColorStop> stops(Object value, List<ColorStop> fallback) {
    if (!(value instanceof List<?> values)) return fallback;
    List<ColorStop> result = new ArrayList<>();
    for (Object item : values) {
      if (!(item instanceof Map<?, ?> stop) || !(stop.get("value") instanceof Number number))
        continue;
      result.add(
          new ColorStop(
              number.doubleValue(),
              string(stop.get("color"), "#000000"),
              decimal(stop.get("opacity"), 1)));
    }
    return List.copyOf(result);
  }

  public record ColorStop(double value, String color, double opacity) {
    public ColorStop {
      opacity = Math.max(0, Math.min(1, opacity));
      color = color != null && color.matches("#[0-9A-Fa-f]{6}") ? color : "#000000";
    }
  }
}
