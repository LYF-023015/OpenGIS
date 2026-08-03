package org.opengis.gis.io;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.tool.api.ToolException;

class WorkspaceGisPathsTest {
  @Test
  void rejectsInputAndOutputTraversal(@TempDir Path workspace) throws Exception {
    Path outside = Files.createTempFile("opengis-outside-", ".geojson");
    try {
      assertThatThrownBy(() -> WorkspaceGisPaths.input(workspace, outside.toString()))
          .isInstanceOf(ToolException.class)
          .hasMessageContaining("workspace");
      assertThatThrownBy(
              () -> WorkspaceGisPaths.output(workspace, "../escape.geojson", "x.geojson"))
          .isInstanceOf(ToolException.class)
          .hasMessageContaining("workspace");
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
