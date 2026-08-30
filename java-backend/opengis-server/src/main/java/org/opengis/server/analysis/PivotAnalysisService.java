package org.opengis.server.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Deterministic, structured Pivot statistics. No source code is accepted or executed. */
@Service
public class PivotAnalysisService {
  private static final int MAX_ROWS = 2_000;
  private static final int MAX_COLUMNS = 48;
  private static final int MAX_DISTRIBUTIONS = 16;
  private static final int MAX_BUCKETS = 12;

  public Map<String, Object> analyze(JsonNode request) {
    Instant started = Instant.now();
    if ("raster".equals(request.path("kind").asString())) {
      return raster(request.path("raster_rows"), started);
    }

    List<Map<String, Object>> rows = rows(request.path("rows"));
    List<String> columns = columns(request.path("columns"), rows);
    List<Map<String, Object>> stats =
        columns.stream().map(field -> fieldStat(field, rows)).toList();
    List<Map<String, Object>> distributions = new ArrayList<>();
    for (Map<String, Object> stat : stats) {
      if (distributions.size() >= MAX_DISTRIBUTIONS) break;
      if (((Number) stat.get("count")).intValue() > 0) {
        distributions.add(
            distribution(
                String.valueOf(stat.get("field")), String.valueOf(stat.get("type")), rows));
      }
    }
    long numeric = stats.stream().filter(stat -> "number".equals(stat.get("type"))).count();
    long categorical =
        stats.stream()
            .filter(stat -> "string".equals(stat.get("type")) || "boolean".equals(stat.get("type")))
            .count();
    long totalRows = Math.max(rows.size(), request.path("total_rows").asLong(rows.size()));
    String sample = totalRows > rows.size() ? "sampled" : "complete";
    return result(
        stats,
        distributions,
        "Java analyzed "
            + rows.size()
            + " / "
            + totalRows
            + " records ("
            + sample
            + ") and "
            + stats.size()
            + " fields: "
            + numeric
            + " numeric and "
            + categorical
            + " categorical/text.",
        started);
  }

  private Map<String, Object> raster(JsonNode rasterRows, Instant started) {
    List<Map<String, Object>> stats = new ArrayList<>();
    if (rasterRows.isArray()) {
      int index = 0;
      for (JsonNode row : rasterRows) {
        if (index++ >= MAX_COLUMNS) break;
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("field", row.path("band").asString("band_" + index));
        stat.put("type", "number");
        stat.put("count", row.path("valid_pixels").asLong());
        stat.put("nullCount", row.path("nodata_pixels").asLong());
        stat.put("uniqueCount", 0);
        putNumber(stat, "min", row.path("min"));
        putNumber(stat, "max", row.path("max"));
        putNumber(stat, "mean", row.path("mean"));
        stats.add(stat);
      }
    }
    return result(
        stats,
        List.of(),
        "Java analyzed " + stats.size() + " raster band(s); review min/max, mean and NoData.",
        started);
  }

  private List<Map<String, Object>> rows(JsonNode node) {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (!node.isArray()) return rows;
    for (JsonNode row : node) {
      if (rows.size() >= MAX_ROWS) break;
      if (!row.isObject()) continue;
      Map<String, Object> values = new LinkedHashMap<>();
      row.properties().forEach(entry -> values.put(entry.getKey(), scalar(entry.getValue())));
      rows.add(values);
    }
    return rows;
  }

  private List<String> columns(JsonNode node, List<Map<String, Object>> rows) {
    Set<String> columns = new LinkedHashSet<>();
    if (node.isArray()) {
      node.forEach(value -> columns.add(value.asString()));
    }
    if (columns.isEmpty()) rows.forEach(row -> columns.addAll(row.keySet()));
    return columns.stream().limit(MAX_COLUMNS).toList();
  }

  private Map<String, Object> fieldStat(String field, List<Map<String, Object>> rows) {
    List<Object> values = rows.stream().map(row -> row.get(field)).toList();
    List<Object> nonNull = values.stream().filter(value -> !isNull(value)).toList();
    List<Double> numbers =
        nonNull.stream().map(this::number).filter(value -> value != null).toList();
    int uniqueCount = new LinkedHashSet<>(nonNull.stream().map(String::valueOf).toList()).size();
    boolean numeric =
        !nonNull.isEmpty() && numbers.size() >= Math.max(3, (int) Math.floor(nonNull.size() * 0.7));
    Map<String, Object> stat = new LinkedHashMap<>();
    stat.put("field", field);
    stat.put("count", nonNull.size());
    stat.put("nullCount", values.size() - nonNull.size());
    stat.put("uniqueCount", uniqueCount);
    if (numeric) {
      stat.put("type", "number");
      stat.put("min", numbers.stream().mapToDouble(value -> value).min().orElse(0));
      stat.put("max", numbers.stream().mapToDouble(value -> value).max().orElse(0));
      stat.put("mean", numbers.stream().mapToDouble(value -> value).average().orElse(0));
      return stat;
    }
    boolean bool =
        !nonNull.isEmpty()
            && nonNull.stream()
                .allMatch(
                    value ->
                        value instanceof Boolean
                            || "true".equalsIgnoreCase(String.valueOf(value))
                            || "false".equalsIgnoreCase(String.valueOf(value)));
    stat.put("type", bool ? "boolean" : "string");
    if (!nonNull.isEmpty()) {
      List<String> strings = nonNull.stream().map(String::valueOf).sorted().toList();
      stat.put("min", strings.getFirst());
      stat.put("max", strings.getLast());
    }
    return stat;
  }

  private Map<String, Object> distribution(
      String field, String type, List<Map<String, Object>> rows) {
    List<Object> values =
        rows.stream().map(row -> row.get(field)).filter(value -> !isNull(value)).toList();
    List<Map<String, Object>> buckets = new ArrayList<>();
    if ("number".equals(type)) {
      List<Double> numbers =
          values.stream().map(this::number).filter(value -> value != null).toList();
      if (!numbers.isEmpty()) buckets.addAll(numericBuckets(numbers));
    } else {
      Map<String, Integer> counts = new LinkedHashMap<>();
      values.forEach(value -> counts.merge(String.valueOf(value), 1, Integer::sum));
      counts.entrySet().stream()
          .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
          .limit(MAX_BUCKETS)
          .forEach(entry -> buckets.add(bucket(entry.getKey(), entry.getValue(), values.size())));
    }
    return Map.of("field", field, "type", type, "buckets", buckets);
  }

  private List<Map<String, Object>> numericBuckets(List<Double> numbers) {
    double min = numbers.stream().mapToDouble(value -> value).min().orElse(0);
    double max = numbers.stream().mapToDouble(value -> value).max().orElse(0);
    if (Double.compare(min, max) == 0)
      return List.of(bucket(format(min), numbers.size(), numbers.size()));
    int size = Math.min(MAX_BUCKETS, Math.max(4, (int) Math.round(Math.sqrt(numbers.size()))));
    int[] counts = new int[size];
    for (double value : numbers) {
      int index = Math.min(size - 1, (int) Math.floor(((value - min) / (max - min)) * size));
      counts[index]++;
    }
    List<Map<String, Object>> buckets = new ArrayList<>();
    for (int index = 0; index < size; index++) {
      double lower = min + ((max - min) * index) / size;
      double upper = min + ((max - min) * (index + 1)) / size;
      buckets.add(bucket(format(lower) + "-" + format(upper), counts[index], numbers.size()));
    }
    return buckets;
  }

  private Map<String, Object> result(
      List<Map<String, Object>> stats,
      List<Map<String, Object>> distributions,
      String summary,
      Instant started) {
    return Map.of(
        "stats", stats,
        "distributions", distributions,
        "summary", summary,
        "duration_ms", Duration.between(started, Instant.now()).toMillis());
  }

  private Map<String, Object> bucket(String label, int count, int total) {
    return Map.of(
        "label", label,
        "count", count,
        "probability", total == 0 ? 0 : ((double) count) / total);
  }

  private Object scalar(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return null;
    if (value.isBoolean()) return value.asBoolean();
    if (value.isIntegralNumber()) return value.asLong();
    if (value.isFloatingPointNumber()) return value.asDouble();
    return value.isString() ? value.asString() : value.toString();
  }

  private boolean isNull(Object value) {
    return value == null || (value instanceof String text && text.isBlank());
  }

  private Double number(Object value) {
    if (value instanceof Number number) return number.doubleValue();
    if (value == null || value instanceof Boolean) return null;
    try {
      double parsed = Double.parseDouble(String.valueOf(value).trim());
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String format(double value) {
    if (Math.abs(value) >= 1_000 || (value != 0 && Math.abs(value) < 0.01)) {
      return String.format(Locale.ROOT, "%.2e", value);
    }
    return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  private void putNumber(Map<String, Object> target, String name, JsonNode value) {
    if (value.isNumber()) target.put(name, value.asDouble());
  }
}
