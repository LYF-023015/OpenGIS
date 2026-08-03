package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opengis.agent.persistence.AgentProfileStore;
import org.opengis.agent.persistence.ArtifactStore;
import org.opengis.agent.persistence.PermissionRuleStore;
import org.opengis.agent.persistence.RunArchive;
import org.opengis.agent.persistence.RunIndex;
import org.opengis.agent.persistence.SessionStore;
import org.opengis.common.protocol.JsonRpcErrorCodes;
import org.opengis.platform.git.GitWorkspaceAdapter;
import org.opengis.platform.migration.WorkspaceMigrationService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Phase 3 persistence methods that replace their explicit Phase 2 placeholders. */
@Component
public class Phase3RpcMethods {
  private final RpcMethodRegistry registry;
  private final ObjectMapper objectMapper;

  public Phase3RpcMethods(RpcMethodRegistry registry, ObjectMapper objectMapper) {
    this.registry = registry;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void registerMethods() {
    register("rpc.agent.sessions.list", this::sessions);
    register("rpc.agent.inbox.list", this::inbox);
    register("rpc.agent.profiles.list", this::profiles);
    register("rpc.agent.artifacts.list", this::artifacts);
    register("rpc.agent.permissions.rules.list", this::permissionRules);
    register("rpc.agent.permissions.rules.add", this::addPermissionRule);
    register("rpc.agent.permissions.rules.remove", this::removePermissionRule);
    register("rpc.runs.list", this::runs);
    register("rpc.runs.get", this::runDetail);
    register("rpc.workspace.revert_run", this::revertRun);
    register("rpc.migration.inspect", this::inspectMigration);
    register("rpc.migration.apply", this::applyMigration);
    register("rpc.migration.rollback", this::rollbackMigration);
  }

  private Object sessions(JsonNode params) {
    ObjectNode state = new SessionStore(workspace(params)).load();
    return Map.of("sessions", limitedValues(state.path("sessions"), limit(params, 100)));
  }

  private Object inbox(JsonNode params) {
    String status = params.path("status").asText();
    List<JsonNode> values =
        limitedValues(new SessionStore(workspace(params)).load().path("inbox"), 10_000);
    if (!status.isBlank()) {
      values = values.stream().filter(item -> status.equals(item.path("status").asText())).toList();
    }
    return Map.of("items", values.stream().limit(limit(params, 100)).toList());
  }

  private Object profiles(JsonNode params) {
    return Map.of("profiles", new AgentProfileStore(workspace(params)).list());
  }

  private Object artifacts(JsonNode params) {
    return Map.of(
        "artifacts",
        new ArtifactStore(workspace(params)).list().stream().limit(limit(params, 200)).toList());
  }

  private Object permissionRules(JsonNode params) {
    return Map.of("rules", new PermissionRuleStore(workspace(params)).list());
  }

  private Object addPermissionRule(JsonNode params) {
    String tool = text(params, "tool", text(params, "pattern", ""));
    String action = text(params, "action", "ask");
    if (tool.isBlank() || !List.of("allow", "ask", "deny").contains(action)) {
      throw invalidParams("tool/pattern and action=allow|ask|deny are required");
    }
    ObjectNode rule = objectMapper.createObjectNode();
    rule.put("id", UUID.randomUUID().toString().replace("-", ""));
    rule.put("tool", tool);
    rule.put("action", action);
    rule.put("scope", text(params, "scope", "workspace"));
    rule.put("reason", text(params, "reason", ""));
    if (params.path("profile_name").isTextual()) {
      rule.put("profile_name", params.path("profile_name").asText());
    } else {
      rule.putNull("profile_name");
    }
    rule.put("created_at", OffsetDateTime.now().toString());
    new PermissionRuleStore(workspace(params)).add(rule);
    return Map.of("status", "ok", "rule", rule);
  }

  private Object removePermissionRule(JsonNode params) {
    String ruleId = text(params, "rule_id", text(params, "id", ""));
    if (ruleId.isBlank()) {
      throw invalidParams("rule_id is required");
    }
    boolean removed = new PermissionRuleStore(workspace(params)).remove(ruleId);
    return Map.of("status", removed ? "ok" : "not_found", "removed", removed);
  }

  private Object runs(JsonNode params) {
    return Map.of(
        "runs",
        RunArchive.list(workspace(params)).stream()
            .limit(limit(params, 50))
            .map(Phase3RpcMethods::runIndex)
            .toList());
  }

  private Object runDetail(JsonNode params) {
    String runId = text(params, "run_id", "");
    if (runId.isBlank()) {
      throw invalidParams("run_id is required");
    }
    RunArchive run =
        RunArchive.load(workspace(params), runId)
            .orElseThrow(() -> invalidParams("run archive not found: " + runId));
    List<ObjectNode> toolEvents = run.read("tool_calls.jsonl");
    return Map.ofEntries(
        Map.entry("status", "ok"),
        Map.entry("meta", run.meta()),
        Map.entry("steps", run.read("steps.jsonl")),
        Map.entry("tool_calls", toolEvents),
        Map.entry("tool_call_events", toolEvents),
        Map.entry("artifacts", run.read("artifacts.jsonl")),
        Map.entry("events", run.read("events.jsonl")),
        Map.entry("message_parts", run.read("message_parts.jsonl")),
        Map.entry("llm_usage", run.read("llm_usage.jsonl")));
  }

  private Object revertRun(JsonNode params) {
    String runId = text(params, "run_id", "");
    if (runId.isBlank()) {
      throw invalidParams("run_id is required");
    }
    Path workspace = workspace(params);
    RunArchive run =
        RunArchive.load(workspace, runId)
            .orElseThrow(() -> invalidParams("run archive not found: " + runId));
    String preSha = run.meta().path("pre_sha").asText();
    if (preSha.isBlank()) {
      throw invalidParams("run archive has no pre_sha: " + runId);
    }
    String resetTo = new GitWorkspaceAdapter().revertHard(workspace, preSha);
    return Map.of("status", "ok", "reset_to", resetTo, "run_id", runId);
  }

  private Object inspectMigration(JsonNode params) {
    return new WorkspaceMigrationService().inspect(workspace(params));
  }

  private Object applyMigration(JsonNode params) {
    return new WorkspaceMigrationService().apply(workspace(params));
  }

  private Object rollbackMigration(JsonNode params) {
    return new WorkspaceMigrationService().rollback(workspace(params));
  }

  private void register(String method, RpcHandler handler) {
    registry.registerOrReplace(method, handler);
  }

  private static Path workspace(JsonNode params) {
    String value = params.path("workspace_path").asText();
    if (value.isBlank()) {
      throw invalidParams("workspace_path is required");
    }
    return Path.of(value).toAbsolutePath().normalize();
  }

  private static int limit(JsonNode params, int fallback) {
    return Math.max(1, Math.min(10_000, params.path("limit").asInt(fallback)));
  }

  private static String text(JsonNode params, String field, String fallback) {
    return params.path(field).isTextual() ? params.path(field).asText() : fallback;
  }

  private static List<JsonNode> limitedValues(JsonNode object, int limit) {
    if (!object.isObject()) {
      return List.of();
    }
    List<JsonNode> values = new ArrayList<>(object.properties().size());
    object.properties().stream().limit(limit).forEach(entry -> values.add(entry.getValue()));
    return values;
  }

  private static Map<String, Object> runIndex(RunIndex run) {
    return Map.ofEntries(
        Map.entry("run_id", run.runId()),
        Map.entry("status", run.status()),
        Map.entry("prompt", run.prompt()),
        Map.entry("created_at", run.createdAt()),
        Map.entry("finished_at", run.finishedAt() == null ? "" : run.finishedAt()),
        Map.entry("step_count", run.stepCount()),
        Map.entry("pre_sha", run.preSha() == null ? "" : run.preSha()),
        Map.entry("post_sha", run.postSha() == null ? "" : run.postSha()));
  }

  private static RpcException invalidParams(String message) {
    return new RpcException(JsonRpcErrorCodes.INVALID_PARAMS, message);
  }
}
