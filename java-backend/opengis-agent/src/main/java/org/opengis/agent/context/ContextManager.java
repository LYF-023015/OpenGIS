package org.opengis.agent.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.opengis.ai.context.CanonicalRequest;
import org.opengis.ai.context.CanonicalRequestBuilder;
import org.opengis.ai.context.PromptCachePolicy;
import org.opengis.ai.context.PromptSectionKind;
import org.opengis.ai.context.PromptStability;
import org.opengis.ai.model.LlmMessage;
import org.opengis.ai.model.LlmRole;
import org.opengis.ai.model.LlmToolCall;
import org.opengis.ai.model.LlmToolDefinition;
import org.opengis.knowledge.context.WorkingState;
import org.opengis.knowledge.memory.MemoryRecord;
import org.opengis.knowledge.memory.MemoryRepository;
import org.opengis.platform.persistence.JsonFileStore;
import org.opengis.platform.persistence.WorkspaceLayout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent-owned assembler joining neutral history with selected knowledge and provider layout. */
public final class ContextManager {
  private final Path workspace;
  private final JsonFileStore files = new JsonFileStore();
  private final MemoryRepository memory;
  private final ConcurrentHashMap<String, ConversationState> states = new ConcurrentHashMap<>();

  public ContextManager(Path workspace) {
    this.workspace = workspace.toAbsolutePath().normalize();
    memory = new MemoryRepository(this.workspace);
  }

  public void append(String conversationId, LlmMessage message) {
    state(conversationId).messages.add(message);
    save(conversationId);
  }

  public List<LlmMessage> messages(String conversationId) {
    return List.copyOf(state(conversationId).messages);
  }

  public void workingState(String conversationId, WorkingState workingState) {
    state(conversationId).workingState = workingState == null ? WorkingState.empty() : workingState;
    save(conversationId);
  }

  public CanonicalRequest buildRequest(
      String conversationId,
      String model,
      String systemPrompt,
      String capabilityManifest,
      String toolProtocol,
      String userPreferences,
      List<LlmToolDefinition> tools,
      double temperature,
      int maxTokens,
      Duration timeout) {
    ConversationState state = state(conversationId);
    List<MemoryRecord> selected = memory.relevant(latestUserTask(state.messages), 8);
    List<LlmMessage> memoryMessages =
        selected.isEmpty()
            ? List.of()
            : List.of(
                LlmMessage.system(
                    "Relevant project memory:\n"
                        + selected.stream()
                            .map(record -> "- [" + record.kind() + "] " + record.content())
                            .collect(java.util.stream.Collectors.joining("\n"))));
    List<LlmMessage> workingMessages =
        state.workingState.equals(WorkingState.empty())
            ? List.of()
            : List.of(LlmMessage.system("Current working state: " + state.workingState));
    return new CanonicalRequestBuilder(model)
        .add(
            "system",
            PromptSectionKind.SYSTEM,
            List.of(LlmMessage.system(systemPrompt)),
            PromptStability.STATIC,
            PromptCachePolicy.CACHEABLE)
        .add(
            "capabilities",
            PromptSectionKind.CAPABILITY_MANIFEST,
            textSection(capabilityManifest),
            PromptStability.WORKSPACE_STATIC,
            PromptCachePolicy.CACHEABLE)
        .add(
            "tool-protocol",
            PromptSectionKind.TOOL_PROTOCOL,
            textSection(toolProtocol),
            PromptStability.STATIC,
            PromptCachePolicy.CACHEABLE)
        .add(
            "user-preferences",
            PromptSectionKind.USER_PREFERENCES,
            textSection(userPreferences),
            PromptStability.WORKSPACE_STATIC,
            PromptCachePolicy.BREAKPOINT)
        .add(
            "memory",
            PromptSectionKind.MEMORY,
            memoryMessages,
            PromptStability.SESSION_STATIC,
            PromptCachePolicy.NONE)
        .add(
            "working-state",
            PromptSectionKind.WORKING_STATE,
            workingMessages,
            PromptStability.TURN_DYNAMIC,
            PromptCachePolicy.NONE)
        .add(
            "history",
            PromptSectionKind.HISTORY,
            state.messages,
            PromptStability.SESSION_STATIC,
            PromptCachePolicy.NONE)
        .tools(tools)
        .options(temperature, maxTokens, timeout, Map.of("cache_stable_prefix", true))
        .build();
  }

  private ConversationState state(String conversationId) {
    String safe = safeId(conversationId);
    return states.computeIfAbsent(safe, this::load);
  }

  private ConversationState load(String conversationId) {
    Path path = path(conversationId);
    ConversationState state = new ConversationState();
    if (!Files.exists(path)) {
      return state;
    }
    JsonNode root = files.readObject(path);
    for (JsonNode node : root.path("messages")) {
      state.messages.add(readMessage(node));
    }
    JsonNode working = root.path("working_state");
    if (working.isObject()) {
      state.workingState =
          new WorkingState(
              working.path("goal").asText(),
              strings(working.path("completed")),
              strings(working.path("pending")),
              working.path("artifacts").isObject()
                  ? files.objectMapper().convertValue(working.path("artifacts"), Map.class)
                  : Map.of());
    }
    return state;
  }

  private LlmMessage readMessage(JsonNode node) {
    LlmRole role;
    try {
      role = LlmRole.valueOf(node.path("role").asText("USER").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      role = LlmRole.USER;
    }
    List<LlmToolCall> calls = new ArrayList<>();
    for (JsonNode call : node.path("toolCalls")) {
      calls.add(
          new LlmToolCall(
              call.path("id").asText(),
              call.path("name").asText(),
              call.path("arguments").isObject()
                  ? call.path("arguments")
                  : files.objectMapper().createObjectNode()));
    }
    return new LlmMessage(
        role,
        node.path("content").asText(),
        node.path("name").asText(),
        node.path("toolCallId").asText(node.path("tool_call_id").asText()),
        calls);
  }

  private synchronized void save(String conversationId) {
    ConversationState state = states.get(safeId(conversationId));
    if (state == null) {
      return;
    }
    ObjectNode root = files.objectMapper().createObjectNode();
    root.put("schema_version", 1);
    ArrayNode messages = root.putArray("messages");
    state.messages.forEach(message -> messages.add(files.objectMapper().valueToTree(message)));
    root.set("working_state", files.objectMapper().valueToTree(state.workingState));
    files.write(path(conversationId), root);
  }

  private Path path(String conversationId) {
    return new WorkspaceLayout(workspace).resolve("contexts/" + safeId(conversationId) + ".json");
  }

  private static String safeId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Unsafe conversation id: " + value);
    }
    return value;
  }

  private static String latestUserTask(List<LlmMessage> messages) {
    for (int index = messages.size() - 1; index >= 0; index--) {
      if (messages.get(index).role() == LlmRole.USER) {
        return messages.get(index).content();
      }
    }
    return "";
  }

  private static List<LlmMessage> textSection(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(LlmMessage.system(value));
  }

  private static List<String> strings(JsonNode node) {
    return node.isArray() ? node.valueStream().map(JsonNode::asText).toList() : List.of();
  }

  private static final class ConversationState {
    private final List<LlmMessage> messages = new ArrayList<>();
    private WorkingState workingState = WorkingState.empty();
  }
}
