package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RpcDispatcherTest {
  private ObjectMapper objectMapper;
  private RpcMethodRegistry registry;
  private RpcDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    registry = new RpcMethodRegistry();
    new CoreRpcMethods(registry).registerMethods();
    dispatcher = new RpcDispatcher(objectMapper, registry);
  }

  @Test
  void returnsPingAndPreservesTheRequestId() {
    JsonNode response =
        json(
            dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"id\":\"ping-1\","
                    + "\"method\":\"rpc.system.ping\",\"params\":{}}"));

    assertThat(response.path("id").asString()).isEqualTo("ping-1");
    assertThat(response.path("result").path("status").asString()).isEqualTo("ok");
    assertThat(response.path("result").path("protocol_version").asString()).isEqualTo("3.0");
    assertThat(response.path("result").path("runtime").asString()).isEqualTo("java");
  }

  @Test
  void mapsParseInvalidUnknownAndNotYetMigratedErrors() {
    assertThat(json(dispatcher.dispatch("{")).path("error").path("code").asInt()).isEqualTo(-32700);
    assertThat(json(dispatcher.dispatch("[]")).path("error").path("code").asInt())
        .isEqualTo(-32600);
    assertThat(
            json(dispatcher.dispatch(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.nope\"," + "\"params\":{}}"))
                .path("error")
                .path("code")
                .asInt())
        .isEqualTo(-32601);
    JsonNode notMigrated =
        json(
            dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"rpc.tool.list\"," + "\"params\":{}}"));
    assertThat(notMigrated.path("error").path("code").asInt()).isEqualTo(-32004);
    assertThat(notMigrated.path("error").path("data").path("method").asString())
        .isEqualTo("rpc.tool.list");
  }

  @Test
  void notificationsRunWithoutProducingAResponse() {
    assertThat(
            dispatcher.dispatch(
                "{\"jsonrpc\":\"2.0\",\"method\":\"event.system.noop\",\"params\":{}}"))
        .isNull();
  }

  @Test
  void malformedObjectWithoutAnIdStillReturnsInvalidRequest() {
    JsonNode response = json(dispatcher.dispatch("{\"jsonrpc\":\"1.0\",\"method\":\"bad\"}"));

    assertThat(response.path("id").isNull()).isTrue();
    assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
  }

  @Test
  void registryContainsTheFrozenPythonInventoryAndPhaseTwoMethods() {
    assertThat(LegacyMethodCatalog.BACKEND_METHODS).hasSize(47);
    assertThat(registry.size()).isEqualTo(49);
  }

  private JsonNode json(Object value) {
    return objectMapper.valueToTree(value);
  }
}
