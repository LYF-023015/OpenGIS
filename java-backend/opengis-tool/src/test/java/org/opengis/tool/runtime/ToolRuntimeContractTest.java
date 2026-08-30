package org.opengis.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolStatus;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.builtin.BuiltinToolCatalog;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.permission.PermissionRuleSource;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.ToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Five outcome contract scenarios are generated for every Phase 4 tool definition. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolRuntimeContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path workspace;

  Stream<ToolDefinition> definitions() {
    return BuiltinToolCatalog.create(mapper).stream().map(OpenGisTool::definition);
  }

  @ParameterizedTest(name = "{0} succeeds")
  @MethodSource("definitions")
  void everyToolHasSuccessContract(ToolDefinition definition) {
    var result =
        runtime(definition, false).execute(call(definition), context(definition, false, true));
    assertThat(result.success()).isTrue();
    assertThat(result.status()).isEqualTo(ToolStatus.COMPLETED);
  }

  @ParameterizedTest(name = "{0} rejects illegal parameters")
  @MethodSource("definitions")
  void everyToolHasInvalidArgumentsContract(ToolDefinition definition) {
    var result =
        runtime(definition, false)
            .execute(
                new ToolCall("invalid", definition.name(), mapper.createArrayNode()),
                context(definition, false, true));
    assertThat(result.error().code()).isEqualTo("invalid_arguments");
  }

  @ParameterizedTest(name = "{0} can be denied")
  @MethodSource("definitions")
  void everyToolHasRejectedContract(ToolDefinition definition) {
    ToolExecutionContext denied =
        new ToolExecutionContext(
            workspace,
            "contract",
            "conversation",
            "test",
            Map.of(definition.name(), PermissionAction.DENY),
            PermissionAction.ALLOW,
            new CancellationToken(),
            ToolEventSink.noop(),
            approvingUi(true));
    var result = runtime(definition, false).execute(call(definition), denied);
    assertThat(result.status()).isEqualTo(ToolStatus.REJECTED);
    assertThat(result.error().code()).isEqualTo("permission_denied");
  }

  @ParameterizedTest(name = "{0} can be cancelled")
  @MethodSource("definitions")
  void everyToolHasCancellationContract(ToolDefinition definition) {
    var result =
        runtime(definition, false).execute(call(definition), context(definition, true, true));
    assertThat(result.status()).isEqualTo(ToolStatus.CANCELLED);
    assertThat(result.error().code()).isEqualTo("tool_cancelled");
  }

  @ParameterizedTest(name = "{0} normalizes exceptions")
  @MethodSource("definitions")
  void everyToolHasExceptionContract(ToolDefinition definition) {
    var result =
        runtime(definition, true).execute(call(definition), context(definition, false, true));
    assertThat(result.status()).isEqualTo(ToolStatus.FAILED);
    assertThat(result.error().code()).isEqualTo("tool_execution_failed");
  }

  private ToolRuntime runtime(ToolDefinition definition, boolean fail) {
    OpenGisTool probe =
        new OpenGisTool() {
          @Override
          public ToolDefinition definition() {
            return definition;
          }

          @Override
          public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
            if (fail) {
              throw new IllegalStateException("contract failure");
            }
            return mapper.valueToTree(Map.of("tool", definition.name(), "ok", true));
          }
        };
    return new ToolRuntime(
        new ToolRegistry().register(probe),
        new JsonSchemaValidator(),
        new PermissionRuntime(PermissionRuleSource.empty()),
        new ArtifactMaterializer(),
        mapper);
  }

  private ToolCall call(ToolDefinition definition) {
    return new ToolCall("contract", definition.name(), validArguments(definition.inputSchema()));
  }

  private ObjectNode validArguments(JsonNode schema) {
    ObjectNode arguments = mapper.createObjectNode();
    for (JsonNode required : schema.path("required")) {
      String name = required.asString();
      arguments.set(name, validValue(schema.path("properties").path(name)));
    }
    return arguments;
  }

  private JsonNode validValue(JsonNode schema) {
    return switch (schema.path("type").asString()) {
      case "string" ->
          schema.path("enum").isArray() && !schema.path("enum").isEmpty()
              ? schema.path("enum").get(0)
              : mapper.valueToTree("value");
      case "integer" -> mapper.valueToTree(Math.max(1, schema.path("minimum").asInt(1)));
      case "number" -> mapper.valueToTree(Math.max(1, schema.path("minimum").asDouble(1)));
      case "boolean" -> mapper.valueToTree(false);
      case "array" -> {
        ArrayNode array = mapper.createArrayNode();
        array.add(validValue(schema.path("items")));
        yield array;
      }
      case "object" -> mapper.createObjectNode();
      default -> mapper.nullNode();
    };
  }

  private ToolExecutionContext context(
      ToolDefinition definition, boolean cancelled, boolean approved) {
    CancellationToken token = new CancellationToken();
    if (cancelled) {
      token.cancel();
    }
    return new ToolExecutionContext(
        workspace,
        "contract",
        "conversation",
        "test",
        Map.of(),
        PermissionAction.ALLOW,
        token,
        ToolEventSink.noop(),
        approvingUi(approved));
  }

  private UiRpcPort approvingUi(boolean approved) {
    return new UiRpcPort() {
      @Override
      public java.util.concurrent.CompletionStage<JsonNode> request(
          String method, JsonNode params, Duration timeout) {
        return CompletableFuture.completedFuture(mapper.valueToTree(Map.of("approved", approved)));
      }

      @Override
      public void notify(String method, JsonNode params) {}
    };
  }
}
