package org.opengis.workflow.migration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.opengis.workflow.WorkflowCodec;
import org.opengis.workflow.model.WorkflowDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Converts data-only v1 fields and reports Python/code hooks that require human replacement. */
public final class WorkflowMigrationService {
  private final ObjectMapper mapper;

  public WorkflowMigrationService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public WorkflowMigrationReport inspect(String raw) {
    try {
      return inspect(mapper.readTree(raw));
    } catch (Exception exception) {
      return new WorkflowMigrationReport(
          "unknown",
          2,
          "invalid",
          List.of(
              new WorkflowMigrationReport.Issue(
                  "error", "invalid_json", "", exception.getMessage())),
          null);
    }
  }

  public WorkflowMigrationReport inspect(JsonNode source) {
    if (source == null || !source.isObject()) {
      return invalid("workflow_not_object", "Workflow must be a JSON object");
    }
    String version =
        source.has("schemaVersion")
            ? source.path("schemaVersion").asText()
            : source.path("schema_version").asText("1");
    if ("2".equals(version)) {
      try {
        WorkflowDocument document = new WorkflowCodec(mapper).parse(source);
        return new WorkflowMigrationReport(
            "2", 2, "already_v2", List.of(), mapper.valueToTree(document));
      } catch (RuntimeException exception) {
        return invalid("invalid_v2", exception.getMessage());
      }
    }

    List<WorkflowMigrationReport.Issue> issues = new ArrayList<>();
    ObjectNode converted = mapper.createObjectNode();
    converted.put("schemaVersion", 2);
    converted.put(
        "id", safeId(source.path("id").asText(slug(source.path("name").asText("workflow")))));
    converted.put("name", source.path("name").asText("Untitled Workflow"));
    converted.put("description", source.path("description").asText(""));
    String now = OffsetDateTime.now().toString();
    converted.put("createdAt", source.path("createdAt").asText(now));
    converted.put("updatedAt", now);
    ArrayNode nodes = converted.putArray("nodes");
    for (JsonNode old : source.path("nodes")) {
      nodes.add(convertNode(old, issues));
    }
    ArrayNode edges = converted.putArray("edges");
    int edgeIndex = 0;
    for (JsonNode old : source.path("edges")) {
      ObjectNode edge = edges.addObject();
      edge.put("id", old.path("id").asText("edge_" + (++edgeIndex)));
      edge.put("source", old.path("source").asText());
      edge.put("sourceHandle", old.path("sourceHandle").asText(""));
      edge.put("target", old.path("target").asText());
      edge.put("targetHandle", old.path("targetHandle").asText(""));
      edge.put("label", old.path("label").asText(""));
    }
    if (source.has("viewport")) converted.set("viewport", source.get("viewport"));
    converted
        .putObject("metadata")
        .put("migratedFrom", version)
        .put("migrationTool", "java-phase6");

    boolean manual = issues.stream().anyMatch(issue -> "manual_required".equals(issue.severity()));
    if (!manual) {
      try {
        new WorkflowCodec(mapper).parse(converted);
      } catch (RuntimeException exception) {
        issues.add(
            new WorkflowMigrationReport.Issue(
                "error", "converted_invalid", "", exception.getMessage()));
      }
    }
    String status =
        manual
            ? "manual_required"
            : issues.stream().anyMatch(i -> "error".equals(i.severity())) ? "invalid" : "converted";
    return new WorkflowMigrationReport(version, 2, status, List.copyOf(issues), converted);
  }

  private ObjectNode convertNode(JsonNode old, List<WorkflowMigrationReport.Issue> issues) {
    ObjectNode node = mapper.createObjectNode();
    String id = old.path("id").asText();
    node.put("id", id);
    node.put(
        "title",
        old.path("title")
            .asText(old.path("label").asText(old.path("task").asText("Untitled Node"))));
    node.put("description", old.path("description").asText(old.path("task").asText("")));
    String script = old.path("scriptPath").asText(old.path("script_path").asText(""));
    String oldType = old.path("nodeType").asText(old.path("type").asText(""));
    String type;
    String ref;
    if (!script.isBlank()) {
      if (script.toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
        type = "java_script";
        ref = script;
      } else {
        type = "agent_task";
        ref = "gis-build";
        issues.add(
            new WorkflowMigrationReport.Issue(
                "manual_required",
                "python_script_reference",
                id,
                "Replace Python script '"
                    + script
                    + "' with a Java tool, operation, or java_script reference."));
      }
    } else if ("tool_call".equals(oldType) && old.path("toolName").isTextual()) {
      type = "tool_call";
      ref = old.path("toolName").asText();
    } else {
      type = "agent_task";
      ref = old.path("profileName").asText("gis-build");
    }
    node.put("type", type);
    node.putObject("execution").put("kind", type).put("ref", ref);
    node.set(
        "inputs", old.path("inputs").isArray() ? old.path("inputs") : mapper.createArrayNode());
    node.set(
        "outputs", old.path("outputs").isArray() ? old.path("outputs") : mapper.createArrayNode());
    node.set(
        "params", old.path("params").isObject() ? old.path("params") : mapper.createObjectNode());
    node.set(
        "position",
        old.path("position").isObject()
            ? old.path("position")
            : mapper.createObjectNode().put("x", 0).put("y", 0));
    node.put(
        "inputContract", old.path("inputContract").asText(old.path("input_contract").asText("")));
    node.put(
        "outputContract",
        old.path("outputContract").asText(old.path("output_contract").asText("")));
    node.putArray("conditions");
    int retries = old.path("maxRetries").asInt(old.path("max_retries").asInt(0));
    node.putObject("retryPolicy").put("maxAttempts", Math.max(1, retries + 1)).put("backoffMs", 0);
    node.put("notes", old.path("notes").asText(""));
    if (old.path("hooks").isArray() && !old.path("hooks").isEmpty()) {
      issues.add(
          new WorkflowMigrationReport.Issue(
              "manual_required",
              "python_hook",
              id,
              "Replace Python Hook expressions with the safe JSON condition DSL."));
    }
    return node;
  }

  private WorkflowMigrationReport invalid(String code, String message) {
    return new WorkflowMigrationReport(
        "unknown",
        2,
        "invalid",
        List.of(new WorkflowMigrationReport.Issue("error", code, "", message)),
        null);
  }

  private static String slug(String value) {
    String result =
        value
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
    return result.isBlank() ? "workflow" : result;
  }

  private static String safeId(String value) {
    return value.matches("[A-Za-z0-9._-]+") ? value : slug(value);
  }
}
