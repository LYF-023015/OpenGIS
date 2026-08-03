package org.opengis.common.protocol;

import java.util.Map;

/** MapLibre-compatible style snapshot. */
public record LayerStyle(
    LayerStyleType type, Map<String, Object> paint, Map<String, Object> layout) {}
