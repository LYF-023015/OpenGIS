package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.server.transport.UiRpcGateway;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import org.opengis.tool.runtime.ArtifactMaterializer;
import org.opengis.tool.runtime.ToolRuntime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class Phase4PythonInteropIT {
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path temporary;

  @Test
  void livePythonRegistryAcceptsJavaCatalogContract() throws Exception {
    RpcMethodRegistry rpc = new RpcMethodRegistry();
    ToolRegistry tools = BuiltinToolCatalog.registry(mapper);
    ToolRuntime runtime =
        new ToolRuntime(
            tools,
            new JsonSchemaValidator(),
            new PermissionRuntime(PermissionRuleSource.empty()),
            new ArtifactMaterializer(),
            mapper);
    new CoreRpcMethods(rpc).registerMethods();
    new Phase4RpcMethods(rpc, tools, runtime, mock(UiRpcGateway.class), mapper).registerMethods();
    RpcDispatcher dispatcher = new RpcDispatcher(mapper, rpc);
    JsonNode response =
        mapper.valueToTree(
            dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"id\":\"interop\",\"method\":\"rpc.tool.list\",\"params\":{}}"));
    Path catalog = temporary.resolve("java-tool-catalog.json");
    Files.writeString(
        catalog, mapper.writeValueAsString(response.path("result")), StandardCharsets.UTF_8);

    Path repository = repositoryRoot();
    Path python = isolatedPython(repository);
    Process process =
        new ProcessBuilder(
                python.toString(),
                repository.resolve("python-backend/tests/phase4_java_tool_contract.py").toString(),
                catalog.toString())
            .directory(repository.resolve("python-backend").toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(finished).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).contains("\"status\": \"ok\"");
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
