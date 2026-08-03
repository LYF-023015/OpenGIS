package org.opengis.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.workflow.migration.WorkflowMigrationService;
import tools.jackson.databind.ObjectMapper;

class Phase6PythonInteropIT {
  @TempDir Path temporary;

  @Test
  void pythonCoexistenceReaderAcceptsJavaV2AndMigrationReport() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var workflow =
        new WorkflowCodec(mapper)
            .parse(
                """
        {"schemaVersion":2,"id":"interop","name":"Interop","nodes":[
          {"id":"a","title":"A","type":"agent_task","execution":{"kind":"agent_task","ref":"gis-build"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}},
          {"id":"b","title":"B","type":"tool_call","execution":{"kind":"tool_call","ref":"read_file"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
        ],"edges":[{"id":"e","source":"a","target":"b"}]}
        """);
    var report =
        new WorkflowMigrationService(mapper)
            .inspect(
                """
        {"schemaVersion":1,"id":"old","name":"Old","nodes":[
          {"id":"x","scriptPath":"scripts/x.py","hooks":[{"expression":"True"}]}
        ],"edges":[]}
        """);
    Path contract = temporary.resolve("phase6-workflow-contract.json");
    Files.writeString(
        contract,
        mapper.writeValueAsString(Map.of("workflow", workflow, "migration_report", report)),
        StandardCharsets.UTF_8);

    Path repository = repositoryRoot();
    Process process =
        new ProcessBuilder(
                isolatedPython(repository).toString(),
                repository
                    .resolve("python-backend/tests/phase6_java_workflow_contract.py")
                    .toString(),
                contract.toString())
            .directory(repository.resolve("python-backend").toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(finished).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).contains("\"status\": \"ok\"", "\"schemaVersion\": 2");
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
