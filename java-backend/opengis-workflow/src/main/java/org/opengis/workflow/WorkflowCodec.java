package org.opengis.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.opengis.workflow.model.WorkflowCondition;
import org.opengis.workflow.model.WorkflowDocument;
import org.opengis.workflow.model.WorkflowEdge;
import org.opengis.workflow.model.WorkflowNode;
import org.opengis.workflow.model.WorkflowPort;
import org.opengis.workflow.validation.WorkflowValidator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict parser and serializer for executable Workflow schema v2 documents. */
public final class WorkflowCodec {
  private final ObjectMapper mapper;
  private final WorkflowValidator validator;

  public WorkflowCodec(ObjectMapper mapper) {
    this(mapper, new WorkflowValidator());
  }

  public WorkflowCodec(ObjectMapper mapper, WorkflowValidator validator) {
    this.mapper = mapper;
    this.validator = validator;
  }

  public WorkflowDocument parse(String raw) {
    try {
      return parse(mapper.readTree(raw));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Invalid workflow JSON", exception);
    }
  }

  public WorkflowDocument parse(JsonNode root) {
    if (!root.isObject()) {
      throw new IllegalArgumentException("Workflow file must contain a JSON object");
    }
    int version = root.path("schemaVersion").asInt(root.path("schema_version").asInt(1));
    if (version != WorkflowDocument.CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Workflow schema v" + version + " is not executable; inspect and convert it first");
    }
    WorkflowDocument document = readV2(root);
    validator.requireValid(document);
    return document;
  }

  public JsonNode toJson(WorkflowDocument document) {
    validator.requireValid(document);
    return mapper.valueToTree(document);
  }

  private WorkflowDocument readV2(JsonNode root) {
    List<WorkflowNode> nodes = root.path("nodes").valueStream().map(this::readNode).toList();
    List<WorkflowEdge> edges =
        root.path("edges")
            .valueStream()
            .map(
                edge ->
                    new WorkflowEdge(
                        text(
                            edge,
                            "id",
                            "edge_" + text(edge, "source", "") + "_" + text(edge, "target", "")),
                        text(edge, "source", ""),
                        text(edge, "sourceHandle", ""),
                        text(edge, "target", ""),
                        text(edge, "targetHandle", ""),
                        text(edge, "label", "")))
            .toList();
    String now = OffsetDateTime.now().toString();
    Map<String, JsonNode> metadata = jsonMap(root.path("metadata"));
    return new WorkflowDocument(
        2,
        text(root, "id", slug(text(root, "name", "workflow"))),
        text(root, "name", "Untitled Workflow"),
        text(root, "description", ""),
        text(root, "createdAt", now),
        text(root, "updatedAt", now),
        nodes,
        edges,
        root.get("viewport"),
        metadata);
  }

  private WorkflowNode readNode(JsonNode node) {
    JsonNode execution = node.path("execution");
    List<WorkflowPort> inputs = readPorts(node.path("inputs"));
    List<WorkflowPort> outputs = readPorts(node.path("outputs"));
    List<WorkflowCondition> conditions =
        node.path("conditions")
            .valueStream()
            .map(
                condition ->
                    new WorkflowCondition(
                        condition.get("expression"),
                        text(condition, "description", ""),
                        text(condition, "onFalse", "fail")))
            .toList();
    JsonNode retry = node.path("retryPolicy");
    Map<String, JsonNode> params = jsonMap(node.path("params"));
    return new WorkflowNode(
        text(node, "id", ""),
        text(node, "title", "Untitled Node"),
        text(node, "description", ""),
        text(node, "type", "agent_task"),
        new WorkflowNode.ExecutionReference(
            text(execution, "kind", text(node, "type", "agent_task")), text(execution, "ref", "")),
        inputs,
        outputs,
        params,
        node.get("position"),
        text(node, "inputContract", ""),
        text(node, "outputContract", ""),
        conditions,
        new WorkflowNode.RetryPolicy(
            retry.path("maxAttempts").asInt(1), retry.path("backoffMs").asLong(0)),
        text(node, "notes", ""));
  }

  private List<WorkflowPort> readPorts(JsonNode ports) {
    return ports
        .valueStream()
        .map(
            port ->
                new WorkflowPort(
                    text(port, "name", ""),
                    text(port, "label", ""),
                    text(port, "type", "Any"),
                    text(port, "description", "")))
        .toList();
  }

  private static String text(JsonNode node, String field, String fallback) {
    return node != null && node.path(field).isTextual() ? node.path(field).asText() : fallback;
  }

  private static String slug(String value) {
    String slug = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    slug = slug.replaceAll("^-+|-+$", "");
    return slug.isBlank() ? "workflow" : slug;
  }

  private static Map<String, JsonNode> jsonMap(JsonNode value) {
    if (!value.isObject()) return Map.of();
    Map<String, JsonNode> result = new java.util.LinkedHashMap<>();
    value.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(result);
  }
}
