/** 文件职责：gis 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.gis.raster;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe byte-bounded LRU cache keyed by raster style revision and XYZ coordinate. */
final class RasterTileCache {
  private final long maxBytes;
  private final Map<Key, byte[]> values = new LinkedHashMap<>(64, 0.75f, true);
  private long bytes;

  RasterTileCache(long maxBytes) {
    this.maxBytes = Math.max(1024 * 1024, maxBytes);
  }

  synchronized byte[] get(Key key) {
    byte[] value = values.get(key);
    return value == null ? null : value.clone();
  }

  synchronized void put(Key key, byte[] value) {
    byte[] previous = values.put(key, value.clone());
    if (previous != null) bytes -= previous.length;
    bytes += value.length;
    var iterator = values.entrySet().iterator();
    while (bytes > maxBytes && iterator.hasNext()) {
      Map.Entry<Key, byte[]> eldest = iterator.next();
      bytes -= eldest.getValue().length;
      iterator.remove();
    }
  }

  synchronized void evictRaster(String rasterId) {
    var iterator = values.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<Key, byte[]> entry = iterator.next();
      if (entry.getKey().rasterId().equals(rasterId)) {
        bytes -= entry.getValue().length;
        iterator.remove();
      }
    }
  }

  synchronized Stats stats() {
    return new Stats(values.size(), bytes, maxBytes);
  }

  record Key(String rasterId, long revision, int z, int x, int y) {}

  record Stats(int entries, long bytes, long maxBytes) {}
}
