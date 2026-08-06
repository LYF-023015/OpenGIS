package org.opengis.ai.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opengis.ai.model.LlmChunk;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.port.LlmException;
import tools.jackson.databind.ObjectMapper;

class LlmStreamAccumulatorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void validToolCallArgumentsAccumulateInProviderOrder() {
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, ignored -> {});
    accumulator.accept(LlmChunk.tool(0, "call-1", "read_file", "{\"path\":\""));
    accumulator.accept(LlmChunk.tool(0, "", "read_file", "C:/data/roads.geojson\"}"));
    accumulator.accept(LlmChunk.tool(1, "call-2", "list_layers", "{}"));
    accumulator.accept(LlmChunk.done("tool_calls"));

    LlmResponse response = accumulator.finish();

    assertThat(response.toolCalls()).hasSize(2);
    assertThat(response.toolCalls())
        .extracting(LlmToolCall::id)
        .containsExactly("call-1", "call-2");
    assertThat(response.toolCalls().getFirst().arguments().path("path").asText())
        .isEqualTo("C:/data/roads.geojson");
  }

  @Test
  void truncatedArgumentsFailWithRetryableMalformedError() {
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, ignored -> {});
    accumulator.accept(LlmChunk.tool(0, "call-1", "read_file", "{\"path\":\"a.geojson\"}"));
    accumulator.accept(LlmChunk.tool(1, "call-2", "add_layer", "{\"path\":\"C:/da"));
    accumulator.accept(LlmChunk.done("tool_calls"));

    assertThatThrownBy(accumulator::finish)
        .isInstanceOf(LlmException.class)
        .satisfies(
            error -> {
              LlmException exception = (LlmException) error;
              assertThat(exception.error().code()).isEqualTo("malformed_tool_arguments");
              assertThat(exception.error().message()).contains("index 1");
              assertThat(exception.error().retryable()).isTrue();
            });
  }

  @Test
  void nonObjectJsonArgumentsAreRejectedAsMalformed() {
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, ignored -> {});
    accumulator.accept(LlmChunk.tool(0, "call-1", "read_file", "\"just a string\""));
    accumulator.accept(LlmChunk.done("tool_calls"));

    assertThatThrownBy(accumulator::finish)
        .isInstanceOf(LlmException.class)
        .satisfies(
            error ->
                assertThat(((LlmException) error).error().code())
                    .isEqualTo("malformed_tool_arguments"));
  }

  @Test
  void missingCallIdGetsIndexFallbackId() {
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, ignored -> {});
    accumulator.accept(LlmChunk.tool(1, "", "list_layers", "{}"));
    accumulator.accept(LlmChunk.done("tool_calls"));

    LlmResponse response = accumulator.finish();

    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.toolCalls().getFirst().id()).isEqualTo("call_1");
    assertThat(response.toolCalls().getFirst().arguments()).isEqualTo(mapper.createObjectNode());
  }

  @Test
  void emptyArgumentsBecomeEmptyObject() {
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, ignored -> {});
    accumulator.accept(LlmChunk.tool(0, "call-1", "list_layers", ""));
    accumulator.accept(LlmChunk.done("tool_calls"));

    LlmResponse response = accumulator.finish();

    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.toolCalls().getFirst().arguments()).isEqualTo(mapper.createObjectNode());
  }

  @Test
  void finishReasonAndTextArePreserved() {
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, ignored -> {});
    accumulator.accept(LlmChunk.text("Looking up"));
    accumulator.accept(LlmChunk.done("stop"));

    LlmResponse response = accumulator.finish();

    assertThat(response.content()).isEqualTo("Looking up");
    assertThat(response.finishReason()).isEqualTo("stop");
    assertThat(response.toolCalls()).isEmpty();
  }

  @Test
  void downstreamChunksAreForwarded() {
    List<LlmChunk> forwarded = new java.util.ArrayList<>();
    LlmStreamAccumulator accumulator = new LlmStreamAccumulator(mapper, forwarded::add);
    accumulator.accept(LlmChunk.text("hello"));

    assertThat(forwarded).hasSize(1);
    assertThat(forwarded.getFirst().text()).isEqualTo("hello");
  }
}
