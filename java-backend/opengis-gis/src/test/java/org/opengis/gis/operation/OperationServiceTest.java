package org.opengis.gis.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.code.runner.ScriptCallbacks;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OperationServiceTest {
  @TempDir Path workspace;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void runsThreeBuiltinsWithBoundedArtifacts() throws Exception {
    Path input = fixture();
    OperationService service = new OperationService(mapper);
    assertThat(service.list(workspace, "", 20).path("operations")).hasSize(3);

    ObjectNode converter = mapper.createObjectNode();
    converter.put("input_path", relative(input));
    converter.put("output_path", "output/points.csv");
    var converted =
        service.run(
            workspace,
            "format_converter",
            converter,
            Duration.ofSeconds(10),
            true,
            new CancellationToken(),
            ScriptCallbacks.disconnected());
    assertThat(converted.path("status").asText()).isEqualTo("success");
    assertThat(workspace.resolve("output/points.csv")).content().contains("geometry_wkt");

    ObjectNode clustering = mapper.createObjectNode();
    clustering.put("input_path", relative(input));
    clustering.put("output_dir", "clusters");
    clustering.put("method", "dbscan");
    clustering.put("eps_meters", 500);
    clustering.put("min_samples", 2);
    var clustered =
        service.run(
            workspace,
            "advanced_clustering",
            clustering,
            Duration.ofSeconds(10),
            true,
            new CancellationToken(),
            ScriptCallbacks.disconnected());
    assertThat(clustered.path("output").path("n_clusters").asInt()).isEqualTo(2);

    ObjectNode density = mapper.createObjectNode();
    density.put("input_path", relative(input));
    density.put("output_dir", "density");
    density.put("bandwidth_meters", 500);
    density.put("cell_size_meters", 250);
    density.put("max_grid_cells", 10_000);
    density.put("output_contours", true);
    var kde =
        service.run(
            workspace,
            "kernel_density",
            density,
            Duration.ofSeconds(10),
            true,
            new CancellationToken(),
            ScriptCallbacks.disconnected());
    assertThat(workspace.resolve(kde.path("output").path("raster_path").asText())).exists();
  }

  @Test
  void workspaceLifecyclePreservesRevisionChecksumAndRunsInChildJvm() {
    OperationService service = new OperationService(mapper);
    ObjectNode spec = mapper.createObjectNode();
    spec.put("operation_id", "learning-example");
    spec.put("name", "Learning Example");
    var created = service.create(workspace, spec, false);
    String checksum = created.path("operation").path("checksum").asText();
    assertThat(
            service
                .validate(workspace, "learning-example", mapper.createObjectNode(), true)
                .path("ok")
                .asBoolean())
        .isTrue();

    ObjectNode edit = mapper.createObjectNode();
    edit.put("description", "revision two");
    var updated = service.edit(workspace, "learning-example", edit);
    assertThat(updated.path("operation").path("revision").asInt()).isEqualTo(2);
    assertThat(updated.path("operation").path("checksum").asText()).isNotEqualTo(checksum);
    assertThat(
            workspace.resolve(
                ".opengis/operations/learning-example/revisions/000001/manifest.json"))
        .exists();

    var run =
        service.run(
            workspace,
            "learning-example",
            mapper.createObjectNode(),
            Duration.ofSeconds(15),
            true,
            new CancellationToken(),
            ScriptCallbacks.disconnected());
    assertThat(run.path("status").asText()).isEqualTo("success");
    assertThat(run.path("revision").asInt()).isEqualTo(2);
    assertThat(run.path("checksum").asText())
        .isEqualTo(updated.path("operation").path("checksum").asText());
  }

  @Test
  void recognizesPythonV1WithoutExecutingIt() {
    Path directory = workspace.resolve(".opengis/operations/legacy");
    new org.opengis.platform.persistence.JsonFileStore(mapper)
        .writeText(directory.resolve("main.py"), "print('legacy')\n");
    new org.opengis.platform.persistence.JsonFileStore(mapper)
        .write(
            directory.resolve("operation.json"),
            mapper.readTree(
                """
                {"schema_version":"1.0","id":"legacy","name":"Legacy","entry":"main.py",
                 "runtime":{"language":"python","dependencies":["geopandas"]},
                 "input_schema":{"type":"object"},"output_schema":{"type":"object"}}
                """));
    OperationService service = new OperationService(mapper);
    assertThat(
            service
                .get(workspace, "legacy", true, 10_000)
                .path("operation")
                .path("compatibility_status")
                .asText())
        .isEqualTo("legacy-python");
    assertThat(service.legacyReport(workspace, "legacy").path("status").asText())
        .isEqualTo("manual_migration_required");
  }

  private Path fixture() throws Exception {
    Path input = workspace.resolve("points.geojson");
    Files.writeString(
        input,
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4700,31.2300]},"properties":{"name":"A"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4710,31.2310]},"properties":{"name":"B"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.5700,31.3300]},"properties":{"name":"C"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.5710,31.3310]},"properties":{"name":"D"}}
        ]}
        """,
        StandardCharsets.UTF_8);
    return input;
  }

  private String relative(Path path) {
    return workspace.relativize(path).toString().replace('\\', '/');
  }
}
