/** 文件职责：agent 后端领域：承载该领域的核心业务流程。 */
package org.opengis.assistant.agent.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.opengis.assistant.model.context.CanonicalRequest;
import org.opengis.assistant.model.context.CanonicalRequestBuilder;
import org.opengis.assistant.model.context.PromptSection.PromptCachePolicy;
import org.opengis.assistant.model.context.PromptSection.PromptSectionKind;
import org.opengis.assistant.model.context.PromptSection.PromptStability;
import org.opengis.assistant.model.LlmMessage;
import org.opengis.assistant.model.LlmRole;
import org.opengis.assistant.model.LlmToolCall;
import org.opengis.assistant.model.LlmToolDefinition;
import org.opengis.assistant.memory.context.WorkingState;
import org.opengis.assistant.memory.MemoryRecord;
import org.opengis.assistant.memory.MemoryRepository;
import org.opengis.assistant.memory.MemoryScope;
import org.opengis.assistant.memory.search.MemorySearchQuery;
import org.opengis.core.persistence.JsonFileStore;
import org.opengis.core.persistence.JsonTypeReferences;
import org.opengis.core.persistence.WorkspaceLayout;
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

  public SummaryBatch summaryBatch(String conversationId, int requestedMessages) {
    ConversationState state = state(conversationId);
    int start = Math.min(state.summarizedMessages, state.messages.size());
    int maximumEnd = Math.max(start, state.messages.size() - 1);
    int end = Math.min(maximumEnd, start + Math.max(0, requestedMessages));
    while (end < maximumEnd && state.messages.get(end).role() == LlmRole.TOOL) {
      end++;
    }
    return new SummaryBatch(
        state.conversationSummary, List.copyOf(state.messages.subList(start, end)), end);
  }

  public void applySummary(String conversationId, String summary, int summarizedThrough) {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("Conversation summary is required");
    }
    ConversationState state = state(conversationId);
    if (summarizedThrough < state.summarizedMessages || summarizedThrough > state.messages.size()) {
      throw new IllegalArgumentException("Invalid conversation summary checkpoint");
    }
    state.conversationSummary = summary.strip();
    state.summarizedMessages = summarizedThrough;
    save(conversationId);
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
    List<MemoryRecord> selected =
        memory
            .search(
                new MemorySearchQuery(
                    latestUserTask(state.messages),
                    8,
                    6_000,
                    Set.of(MemoryScope.GLOBAL, MemoryScope.WORKSPACE, MemoryScope.CONVERSATION),
                    conversationId,
                    ""))
            .stream()
            .map(result -> result.record())
            .toList();
    List<LlmMessage> memoryMessages =
        selected.isEmpty()
            ? List.of()
            : List.of(
                LlmMessage.system(
                    "Relevant project memory:\n"
                        + selected.stream()
                            .map(
                                record ->
                                    "- ["
                                        + record.scope()
                                        + "/"
                                        + record.kind()
                                        + ", confidence="
                                        + String.format(
                                            java.util.Locale.ROOT, "%.2f", record.confidence())
                                        + "] "
                                        + record.content())
                            .collect(java.util.stream.Collectors.joining("\n"))));
    List<LlmMessage> workingMessages =
        state.workingState.equals(WorkingState.empty())
            ? List.of()
            : List.of(LlmMessage.system("Current working state: " + state.workingState));
    List<LlmMessage> summaryMessages =
        state.conversationSummary.isBlank()
            ? List.of()
            : List.of(
                LlmMessage.system(
                    "Conversation summary (authoritative only for earlier compacted turns):\n"
                        + state.conversationSummary));
    List<LlmMessage> activeHistory =
        state.messages.subList(
            Math.min(state.summarizedMessages, state.messages.size()), state.messages.size());
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
            "conversation-summary",
            PromptSectionKind.CONVERSATION_SUMMARY,
            summaryMessages,
            PromptStability.SESSION_STATIC,
            PromptCachePolicy.NONE)
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
            repairedHistory(activeHistory),
            PromptStability.SESSION_STATIC,
            PromptCachePolicy.NONE)
        .tools(tools)
        .options(temperature, maxTokens, timeout, Map.of("cache_stable_prefix", true))
        .build();
  }

  /**
   * Drops incomplete tool-call turns so provider history always passes OpenAI-style validation (an
   * assistant tool_calls message must be fully answered by the tool messages that follow it). Runs
   * interrupted mid-settlement leave a dangling assistant tool_calls message; this repair is
   * idempotent and also heals already-broken persisted conversations.
   */
  private static List<LlmMessage> repairedHistory(List<LlmMessage> messages) {
    List<LlmMessage> repaired = new ArrayList<>(messages.size());
    int index = 0;
    while (index < messages.size()) {
      LlmMessage message = messages.get(index);
      if (message.role() == LlmRole.ASSISTANT && !message.toolCalls().isEmpty()) {
        Set<String> pending = new HashSet<>();
        for (LlmToolCall call : message.toolCalls()) {
          pending.add(call.id());
        }
        int next = index + 1;
        while (next < messages.size() && messages.get(next).role() == LlmRole.TOOL) {
          LlmMessage tool = messages.get(next);
          if (!tool.toolCallId().isBlank() && pending.remove(tool.toolCallId())) {
            next++;
          } else {
            break;
          }
        }
        if (pending.isEmpty()) {
          repaired.add(message);
          for (int t = index + 1; t < next; t++) {
            repaired.add(messages.get(t));
          }
          index = next;
          continue;
        }
        index = next;
        continue;
      }
      if (message.role() == LlmRole.TOOL) {
        index++;
        continue;
      }
      repaired.add(message);
      index++;
    }
    return repaired;
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
              working.path("goal").asString(),
              strings(working.path("completed")),
              strings(working.path("pending")),
              working.path("artifacts").isObject()
                  ? files
                      .objectMapper()
                      .convertValue(working.path("artifacts"), JsonTypeReferences.STRING_MAP)
                  : Map.of());
    }
    JsonNode summary = root.path("conversation_summary");
    if (summary.isObject()) {
      state.conversationSummary = summary.path("content").asString();
      state.summarizedMessages =
          Math.min(state.messages.size(), Math.max(0, summary.path("summarized_messages").asInt()));
    }
    return state;
  }

  private LlmMessage readMessage(JsonNode node) {
    LlmRole role;
    try {
      role = LlmRole.valueOf(node.path("role").asString("USER").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      role = LlmRole.USER;
    }
    List<LlmToolCall> calls = new ArrayList<>();
    for (JsonNode call : node.path("toolCalls")) {
      calls.add(
          new LlmToolCall(
              call.path("id").asString(),
              call.path("name").asString(),
              call.path("arguments").isObject()
                  ? call.path("arguments")
                  : files.objectMapper().createObjectNode()));
    }
    return new LlmMessage(
        role,
        node.path("content").asString(),
        node.path("name").asString(),
        node.path("toolCallId").asString(node.path("tool_call_id").asString()),
        calls);
  }

  private synchronized void save(String conversationId) {
    ConversationState state = states.get(safeId(conversationId));
    if (state == null) {
      return;
    }
    ObjectNode root = files.objectMapper().createObjectNode();
    root.put("schema_version", 2);
    ArrayNode messages = root.putArray("messages");
    state.messages.forEach(message -> messages.add(files.objectMapper().valueToTree(message)));
    root.set("working_state", files.objectMapper().valueToTree(state.workingState));
    ObjectNode summary = root.putObject("conversation_summary");
    summary.put("content", state.conversationSummary);
    summary.put("summarized_messages", state.summarizedMessages);
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
    return node.isArray() ? node.valueStream().map(JsonNode::asString).toList() : List.of();
  }

  private static final class ConversationState {
    private final List<LlmMessage> messages = new ArrayList<>();
    private WorkingState workingState = WorkingState.empty();
    private String conversationSummary = "";
    private int summarizedMessages;
  }

  public record SummaryBatch(
      String existingSummary, List<LlmMessage> messages, int summarizedThrough) {
    public SummaryBatch {
      existingSummary = existingSummary == null ? "" : existingSummary;
      messages = messages == null ? List.of() : List.copyOf(messages);
      summarizedThrough = Math.max(0, summarizedThrough);
    }
  }
}
