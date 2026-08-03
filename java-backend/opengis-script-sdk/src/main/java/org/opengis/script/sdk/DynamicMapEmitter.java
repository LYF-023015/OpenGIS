package org.opengis.script.sdk;

import java.util.List;
import java.util.Map;

/** Typed helper for full/diff dynamic-layer events with stable sequence fields. */
public final class DynamicMapEmitter {
  private final MapClient map;

  DynamicMapEmitter(MapClient map) {
    this.map = map;
  }

  public void full(
      String layerId,
      String name,
      Map<String, Object> featureCollection,
      Map<String, Object> style,
      long sequence) {
    requireLayer(layerId, sequence);
    map.emit(
        "rpc.ui.map.dynamic_layer_update",
        Map.of(
            "layer_id",
            layerId,
            "name",
            name == null ? layerId : name,
            "mode",
            "full",
            "geojson",
            featureCollection,
            "style",
            style == null ? Map.of() : style,
            "sequence",
            sequence));
  }

  public void diff(
      String layerId,
      List<Map<String, Object>> add,
      List<Map<String, Object>> update,
      List<String> remove,
      long sequence) {
    requireLayer(layerId, sequence);
    map.emit(
        "rpc.ui.map.dynamic_layer_update",
        Map.of(
            "layer_id",
            layerId,
            "mode",
            "diff",
            "diff",
            Map.of(
                "add", add == null ? List.of() : add,
                "update", update == null ? List.of() : update,
                "remove", remove == null ? List.of() : remove),
            "sequence",
            sequence));
  }

  private static void requireLayer(String layerId, long sequence) {
    if (layerId == null || layerId.isBlank())
      throw new IllegalArgumentException("layerId is required");
    if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
  }
}
