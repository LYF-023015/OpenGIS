package org.opengis.ai.provider;

import java.util.Locale;
import java.util.stream.Collectors;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmRequest;
import org.opengis.ai.model.LlmRole;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.model.LlmToolDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The only boundary which turns neutral messages and schemas into provider JSON. */
public final class ProviderProjector {
  private final ObjectMapper mapper;

  public ProviderProjector(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ObjectNode openAi(LlmRequest request) {
    ObjectNode root = mapper.createObjectNode();
    root.put("model", request.model());
    root.put("stream", true);
    root.put("temperature", request.temperature());
    root.put("max_tokens", request.maxTokens());
    root.putObject("stream_options").put("include_usage", true);
    ArrayNode messages = root.putArray("messages");
    request.messages().forEach(message -> messages.add(openAiMessage(message)));
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      request.tools().forEach(tool -> tools.add(openAiTool(tool)));
      root.put("tool_choice", "auto");
    }
    return root;
  }

  public ObjectNode anthropic(LlmRequest request) {
    ObjectNode root = mapper.createObjectNode();
    root.put("model", request.model());
    root.put("stream", true);
    root.put("temperature", request.temperature());
    root.put("max_tokens", request.maxTokens());
    String system =
        request.messages().stream()
            .filter(message -> message.role() == LlmRole.SYSTEM)
            .map(LlmMessage::content)
            .collect(Collectors.joining("\n\n"));
    if (!system.isBlank()) {
      ArrayNode blocks = root.putArray("system");
      ObjectNode block = blocks.addObject();
      block.put("type", "text");
      block.put("text", system);
      if (Boolean.TRUE.equals(request.metadata().get("cache_stable_prefix"))) {
        block.putObject("cache_control").put("type", "ephemeral");
      }
    }
    ArrayNode messages = root.putArray("messages");
    request.messages().stream()
        .filter(message -> message.role() != LlmRole.SYSTEM)
        .forEach(message -> messages.add(anthropicMessage(message)));
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      request.tools().forEach(tool -> tools.add(anthropicTool(tool)));
    }
    return root;
  }

  private ObjectNode openAiMessage(LlmMessage message) {
    ObjectNode value = mapper.createObjectNode();
    value.put("role", message.role().name().toLowerCase(Locale.ROOT));
    if (!message.content().isEmpty() || message.toolCalls().isEmpty()) {
      value.put("content", message.content());
    }
    if (!message.name().isBlank()) {
      value.put("name", message.name());
    }
    if (!message.toolCallId().isBlank()) {
      value.put("tool_call_id", message.toolCallId());
    }
    if (!message.toolCalls().isEmpty()) {
      ArrayNode calls = value.putArray("tool_calls");
      message.toolCalls().forEach(call -> calls.add(openAiToolCall(call)));
    }
    return value;
  }

  private ObjectNode openAiToolCall(LlmToolCall call) {
    ObjectNode value = mapper.createObjectNode();
    value.put("id", call.id());
    value.put("type", "function");
    ObjectNode function = value.putObject("function");
    function.put("name", call.name());
    function.put("arguments", mapper.writeValueAsString(call.arguments()));
    return value;
  }

  private ObjectNode openAiTool(LlmToolDefinition tool) {
    ObjectNode value = mapper.createObjectNode();
    value.put("type", "function");
    ObjectNode function = value.putObject("function");
    function.put("name", tool.name());
    function.put("description", tool.description());
    function.set("parameters", tool.inputSchema());
    return value;
  }

  private ObjectNode anthropicMessage(LlmMessage message) {
    ObjectNode value = mapper.createObjectNode();
    value.put("role", message.role() == LlmRole.ASSISTANT ? "assistant" : "user");
    if (message.role() == LlmRole.TOOL) {
      ArrayNode content = value.putArray("content");
      ObjectNode result = content.addObject();
      result.put("type", "tool_result");
      result.put("tool_use_id", message.toolCallId());
      result.put("content", message.content());
      return value;
    }
    if (!message.toolCalls().isEmpty()) {
      ArrayNode content = value.putArray("content");
      if (!message.content().isBlank()) {
        content.addObject().put("type", "text").put("text", message.content());
      }
      for (LlmToolCall call : message.toolCalls()) {
        ObjectNode block = content.addObject();
        block.put("type", "tool_use");
        block.put("id", call.id());
        block.put("name", call.name());
        block.set("input", call.arguments());
      }
    } else {
      value.put("content", message.content());
    }
    return value;
  }

  private ObjectNode anthropicTool(LlmToolDefinition tool) {
    ObjectNode value = mapper.createObjectNode();
    value.put("name", tool.name());
    value.put("description", tool.description());
    value.set("input_schema", tool.inputSchema());
    return value;
  }
}
