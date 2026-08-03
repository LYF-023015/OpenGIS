package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class Phase3RpcMethodsTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private RpcDispatcher dispatcher;
  private Path workspace;

  @BeforeEach
  void setUp() {
    RpcMethodRegistry registry = new RpcMethodRegistry();
    new CoreRpcMethods(registry).registerMethods();
    new Phase3RpcMethods(registry, objectMapper).registerMethods();
    dispatcher = new RpcDispatcher(objectMapper, registry);
    workspace = repositoryRoot().resolve("test/phase0/fixtures/opengis-workspace");
  }

  @Test
  void replacesPersistencePlaceholdersWithFixtureBackedResults() {
    assertThat(call("rpc.agent.sessions.list").path("result").path("sessions")).hasSize(1);
    assertThat(call("rpc.agent.inbox.list").path("result").path("items")).hasSize(1);
    assertThat(call("rpc.agent.profiles.list").path("result").path("profiles")).hasSize(5);
    assertThat(call("rpc.agent.permissions.rules.list").path("result").path("rules")).hasSize(1);
    assertThat(call("rpc.agent.artifacts.list").path("result").path("artifacts")).hasSize(1);
    assertThat(call("rpc.runs.list").path("result").path("runs")).hasSize(1);
    assertThat(callWithRunId("rpc.runs.get").path("result").path("meta").path("run_id").asText())
        .isEqualTo("run-phase0-001");
    assertThat(call("rpc.migration.inspect").path("result").path("applicable").asBoolean())
        .isTrue();
  }

  private JsonNode call(String method) {
    String payload =
        "{\"jsonrpc\":\"2.0\",\"id\":\"phase3\",\"method\":\""
            + method
            + "\",\"params\":{\"workspace_path\":"
            + quote(workspace.toString())
            + "}}";
    return objectMapper.valueToTree(dispatcher.dispatch(payload));
  }

  private JsonNode callWithRunId(String method) {
    String payload =
        "{\"jsonrpc\":\"2.0\",\"id\":\"phase3-run\",\"method\":\""
            + method
            + "\",\"params\":{\"workspace_path\":"
            + quote(workspace.toString())
            + ",\"run_id\":\"run-phase0-001\"}}";
    return objectMapper.valueToTree(dispatcher.dispatch(payload));
  }

  private String quote(String value) {
    return objectMapper.writeValueAsString(value);
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("package.json"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Cannot locate repository root");
  }
}
