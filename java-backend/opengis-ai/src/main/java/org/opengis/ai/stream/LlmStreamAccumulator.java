package org.opengis.ai.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.opengis.ai.model.LlmChunk;
import org.opengis.ai.model.LlmChunkKind;
import org.opengis.ai.model.LlmError;
import org.opengis.ai.model.LlmResponse;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.model.LlmUsage;
import org.opengis.ai.port.LlmException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Assembles text and partial function arguments without exposing wire-specific chunks. */
public final class LlmStreamAccumulator {
  private final ObjectMapper mapper;
  private final Consumer<LlmChunk> downstream;
  private final StringBuilder text = new StringBuilder();
  private final Map<Integer, PartialToolCall> tools = new HashMap<>();
  private LlmUsage usage = LlmUsage.EMPTY;
  private String finishReason = "stop";

  public LlmStreamAccumulator(ObjectMapper mapper, Consumer<LlmChunk> downstream) {
    this.mapper = mapper;
    this.downstream = downstream == null ? ignored -> {} : downstream;
  }

  public void accept(LlmChunk chunk) {
    downstream.accept(chunk);
    if (chunk.kind() == LlmChunkKind.TEXT) {
      text.append(chunk.text());
    } else if (chunk.kind() == LlmChunkKind.TOOL_CALL) {
      PartialToolCall call =
          tools.computeIfAbsent(chunk.toolIndex(), ignored -> new PartialToolCall());
      if (!chunk.toolCallId().isBlank()) {
        call.id = chunk.toolCallId();
      }
      call.name.append(chunk.toolName());
      call.arguments.append(chunk.argumentsDelta());
    } else if (chunk.kind() == LlmChunkKind.USAGE) {
      usage =
          new LlmUsage(
              Math.max(usage.promptTokens(), chunk.usage().promptTokens()),
              Math.max(usage.completionTokens(), chunk.usage().completionTokens()),
              Math.max(usage.cachedTokens(), chunk.usage().cachedTokens()),
              Math.max(usage.cacheReadTokens(), chunk.usage().cacheReadTokens()),
              Math.max(usage.cacheCreationTokens(), chunk.usage().cacheCreationTokens()));
    } else if (chunk.kind() == LlmChunkKind.DONE && !chunk.finishReason().isBlank()) {
      finishReason = chunk.finishReason();
    }
  }

  public LlmResponse finish() {
    List<LlmToolCall> calls = new ArrayList<>();
    tools.entrySet().stream()
        .sorted(Comparator.comparingInt(Map.Entry::getKey))
        .forEach(entry -> calls.add(entry.getValue().finish(mapper, entry.getKey())));
    return new LlmResponse(text.toString(), calls, finishReason, usage);
  }

  private static final class PartialToolCall {
    private String id = "";
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    private LlmToolCall finish(ObjectMapper mapper, int index) {
      String raw = arguments.isEmpty() ? "{}" : arguments.toString();
      try {
        JsonNode parsed = mapper.readTree(raw);
        if (!parsed.isObject()) {
          throw malformed(index, raw, null);
        }
        return new LlmToolCall(id.isBlank() ? "call_" + index : id, name.toString(), parsed);
      } catch (JacksonException exception) {
        throw malformed(index, raw, exception);
      }
    }

    private static LlmException malformed(int index, String raw, Throwable cause) {
      LlmError error =
          new LlmError(
              "malformed_tool_arguments",
              "Provider returned malformed JSON arguments for tool call index " + index,
              true,
              0);
      return cause == null ? new LlmException(error) : new LlmException(error, cause);
    }
  }
}
