/** 文件职责：gis 后端领域：验证对应功能的行为与边界。 */
package org.opengis.gis.io;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.gis.error.GisException;

class WorkspaceGisPathsTest {
  @Test
  void rejectsInputAndOutputTraversal(@TempDir Path workspace) throws Exception {
    Path outside = Files.createTempFile("opengis-outside-", ".geojson");
    try {
      assertThatThrownBy(() -> WorkspaceGisPaths.input(workspace, outside.toString()))
          .isInstanceOf(GisException.class)
          .hasMessageContaining("workspace");
      assertThatThrownBy(
              () -> WorkspaceGisPaths.output(workspace, "../escape.geojson", "x.geojson"))
          .isInstanceOf(GisException.class)
          .hasMessageContaining("workspace");
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
