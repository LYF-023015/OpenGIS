package org.opengis.gis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.vector.VectorLoader;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.ObjectMapper;

class Phase7PythonInteropIT {
  @TempDir Path temporary;

  @Test
  void pythonAndJavaMetadataStayInsideApprovedTolerances() throws Exception {
    Path source = temporary.resolve("interop.geojson");
    Files.writeString(
        source,
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4,31.2]},"properties":{"name":"A"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.5,31.3]},"properties":{"name":"B"}}
        ]}
        """,
        StandardCharsets.UTF_8);
    ObjectMapper mapper = new ObjectMapper();
    var metadata =
        new VectorLoader(mapper, new CrsService()).metadata(source, 100, new CancellationToken());
    Path payload = temporary.resolve("phase7-input.json");
    Path report = temporary.resolve("phase7-difference-report.json");
    Files.writeString(
        payload,
        mapper.writeValueAsString(Map.of("source", source.toString(), "java", metadata)),
        StandardCharsets.UTF_8);

    Path repository = repositoryRoot();
    Process process =
        new ProcessBuilder(
                isolatedPython(repository).toString(),
                repository.resolve("python-backend/tests/phase7_java_gis_contract.py").toString(),
                payload.toString(),
                report.toString())
            .directory(repository.resolve("python-backend").toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(finished).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(mapper.readTree(report.toFile()).path("status").asText()).isEqualTo("approved");
  }

  private static Path isolatedPython(Path repository) {
    Path python =
        repository.resolve(
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "python-backend/.venv/Scripts/python.exe"
                : "python-backend/.venv/bin/python");
    assertThat(python).as("isolated Python environment").exists();
    return python;
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("package.json"))
          && Files.exists(candidate.resolve("java-backend/pom.xml"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Cannot locate repository root");
  }
}
