package org.opengis.workflow.validation;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opengis.workflow.model.WorkflowDocument;
import org.opengis.workflow.model.WorkflowEdge;
import org.opengis.workflow.model.WorkflowNode;

/** Structural, DAG, reference, and resource-limit validation for Workflow schema v2. */
public final class WorkflowValidator {
  public static final int MAX_NODES = 500;
  public static final int MAX_EDGES = 2_000;

  public List<String> errors(WorkflowDocument workflow) {
    List<String> errors = new ArrayList<>();
    if (workflow.schemaVersion() != WorkflowDocument.CURRENT_SCHEMA_VERSION) {
      errors.add("schemaVersion must be 2");
    }
    if (blank(workflow.id()) || !workflow.id().matches("[A-Za-z0-9._-]+")) {
      errors.add("id must use only letters, numbers, dot, underscore, or dash");
    }
    if (blank(workflow.name())) {
      errors.add("name is required");
    }
    if (workflow.nodes().size() > MAX_NODES || workflow.edges().size() > MAX_EDGES) {
      errors.add("workflow exceeds node/edge safety limits");
    }
    Set<String> ids = new HashSet<>();
    for (WorkflowNode node : workflow.nodes()) {
      if (blank(node.id()) || !node.id().matches("[A-Za-z0-9._-]+")) {
        errors.add("unsafe node id: " + node.id());
      } else if (!ids.add(node.id())) {
        errors.add("duplicate node id: " + node.id());
      }
      if (!WorkflowNode.TYPES.contains(node.type())) {
        errors.add("unsupported node type for " + node.id() + ": " + node.type());
      }
      if (node.execution() == null || blank(node.execution().kind())) {
        errors.add("execution reference is required for node " + node.id());
      } else {
        validateExecutionReference(node, errors);
      }
    }
    for (WorkflowEdge edge : workflow.edges()) {
      if (!ids.contains(edge.source()) || !ids.contains(edge.target())) {
        errors.add("edge references missing node: " + edge.source() + " -> " + edge.target());
      }
      if (edge.source() != null && edge.source().equals(edge.target())) {
        errors.add("self edge is not allowed: " + edge.source());
      }
    }
    if (errors.stream().noneMatch(message -> message.startsWith("edge references"))) {
      try {
        topologicalOrder(workflow);
      } catch (WorkflowValidationException exception) {
        errors.addAll(exception.errors());
      }
    }
    return List.copyOf(errors);
  }

  public void requireValid(WorkflowDocument workflow) {
    List<String> errors = errors(workflow);
    if (!errors.isEmpty()) {
      throw new WorkflowValidationException(errors);
    }
  }

  public List<WorkflowNode> topologicalOrder(WorkflowDocument workflow) {
    Map<String, WorkflowNode> nodes = new HashMap<>();
    Map<String, Integer> indegree = new HashMap<>();
    Map<String, List<String>> successors = new HashMap<>();
    for (WorkflowNode node : workflow.nodes()) {
      nodes.put(node.id(), node);
      indegree.put(node.id(), 0);
      successors.put(node.id(), new ArrayList<>());
    }
    for (WorkflowEdge edge : workflow.edges()) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        throw new WorkflowValidationException(List.of("edge references missing node"));
      }
      successors.get(edge.source()).add(edge.target());
      indegree.compute(edge.target(), (ignored, value) -> value == null ? 1 : value + 1);
    }
    ArrayDeque<String> ready = new ArrayDeque<>();
    workflow.nodes().stream()
        .map(WorkflowNode::id)
        .filter(id -> indegree.get(id) == 0)
        .forEach(ready::add);
    List<WorkflowNode> ordered = new ArrayList<>();
    while (!ready.isEmpty()) {
      String id = ready.removeFirst();
      ordered.add(nodes.get(id));
      for (String successor : successors.get(id)) {
        int remaining = indegree.compute(successor, (ignored, value) -> value - 1);
        if (remaining == 0) {
          ready.add(successor);
        }
      }
    }
    if (ordered.size() != workflow.nodes().size()) {
      throw new WorkflowValidationException(List.of("workflow graph contains a cycle"));
    }
    return List.copyOf(ordered);
  }

  private static void validateExecutionReference(WorkflowNode node, List<String> errors) {
    String kind = node.execution().kind();
    String ref = node.execution().ref();
    if (!node.type().equals(kind)) {
      errors.add("execution kind must match node type for " + node.id());
    }
    if (blank(ref)) {
      errors.add("execution ref is required for node " + node.id());
    }
    if ("java_script".equals(kind)) {
      if (ref == null || !ref.toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
        errors.add("java_script ref must end in .java for node " + node.id());
      } else {
        Path path = Path.of(ref).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
          errors.add("java_script ref must be workspace-relative for node " + node.id());
        }
      }
    }
    if (ref != null && ref.toLowerCase(java.util.Locale.ROOT).endsWith(".py")) {
      errors.add("Python execution references are not allowed in schema v2: " + node.id());
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
