package org.opengis.gis.vector;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.gis.crs.CrsService;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.context.CancellationToken;
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
    CancellationToken cancellation = new CancellationToken();
    cancellation.cancel();
    VectorLoader loader = new VectorLoader(new ObjectMapper(), new CrsService());
    assertThatThrownBy(() -> loader.load(path, 100_000, cancellation))
        .isInstanceOf(ToolException.class)
        .hasMessageContaining("cancelled");
  }

  @Test
  void rejectsOversizedInputBeforeAllocatingItsContents(@TempDir Path workspace) throws Exception {
    Path path = workspace.resolve("oversized.geojson");
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      file.setLength(VectorLoader.DEFAULT_MAX_INPUT_BYTES + 1);
    }
    VectorLoader loader = new VectorLoader(new ObjectMapper(), new CrsService());
    assertThatThrownBy(() -> loader.load(path, 100_000, new CancellationToken()))
        .isInstanceOf(ToolException.class)
        .hasMessageContaining("64 MiB");
  }
}
