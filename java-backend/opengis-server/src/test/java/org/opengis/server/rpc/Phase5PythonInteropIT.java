package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.ai.provider.ProviderCatalog;
import org.opengis.ai.provider.ProviderProtocol;
import tools.jackson.databind.ObjectMapper;

class Phase5PythonInteropIT {
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path temporary;

  @Test
  void javaCatalogMatchesFrozenPythonMigrationLedger() throws Exception {
    Path catalog = temporary.resolve("java-provider-catalog.json");
    Map<String, Object> payload =
        Map.of(
            "providers",
            ProviderCatalog.presets().stream()
                .map(
                    preset ->
                        Map.of(
                            "id", preset.id(),
                            "protocol", preset.protocol().name().toLowerCase(java.util.Locale.ROOT),
                            "base_url", preset.baseUrl(),
                            "default_model", preset.defaultModel(),
                            "decision", "migrate",
                            "adapter",
                                preset.protocol() == ProviderProtocol.ANTHROPIC
                                    ? "anthropic-compatible"
                                    : "openai-compatible"))
                .toList());
    Files.writeString(catalog, mapper.writeValueAsString(payload), StandardCharsets.UTF_8);

    Path repository = repositoryRoot();
    Process process =
        new ProcessBuilder(
                isolatedPython(repository).toString(),
                repository
                    .resolve("python-backend/tests/phase5_java_provider_contract.py")
                    .toString(),
                catalog.toString())
            .directory(repository.resolve("python-backend").toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(finished).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).contains("\"status\": \"ok\"", "\"providers\": 24");
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
          && Files.exists(candidate.resolve("java-backend/pom.xml"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Cannot locate repository root");
  }
}
