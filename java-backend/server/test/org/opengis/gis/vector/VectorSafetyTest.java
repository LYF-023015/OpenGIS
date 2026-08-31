/** 文件职责：gis 后端领域：验证对应功能的行为与边界。 */
package org.opengis.gis.vector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.core.concurrent.CancellationSource;
import org.opengis.core.concurrent.OperationCancelledException;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.error.GisException;
import tools.jackson.databind.ObjectMapper;

class VectorSafetyTest {
  @Test
  void honorsCancellationBeforeLargeGeoJsonTraversal(@TempDir Path workspace) throws Exception {
    Path path = workspace.resolve("large.geojson");
    Files.writeString(
        path,
        "{\"type\":\"FeatureCollection\",\"features\":[{"
            + "\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,0]},\"properties\":{}}]}",
        StandardCharsets.UTF_8);
    CancellationSource cancellation = new CancellationSource();
    cancellation.cancel();
    VectorLoader loader = new VectorLoader(new ObjectMapper(), new CrsService());
    assertThatThrownBy(() -> loader.load(path, 100_000, cancellation))
        .isInstanceOf(OperationCancelledException.class)
        .hasMessageContaining("cancelled");
  }

  @Test
  void rejectsOversizedInputBeforeAllocatingItsContents(@TempDir Path workspace) throws Exception {
    Path path = workspace.resolve("oversized.geojson");
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength(VectorLoader.DEFAULT_MAX_INPUT_BYTES + 1);
    }
    VectorLoader loader = new VectorLoader(new ObjectMapper(), new CrsService());
    assertThatThrownBy(() -> loader.load(path, 100_000, new CancellationSource()))
        .isInstanceOf(GisException.class)
        .hasMessageContaining("64 MiB");
  }
}
