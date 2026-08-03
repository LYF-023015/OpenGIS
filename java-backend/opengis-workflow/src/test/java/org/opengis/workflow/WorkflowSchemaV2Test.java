package org.opengis.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opengis.workflow.migration.WorkflowMigrationService;
import org.opengis.workflow.validation.SafeConditionEvaluator;
import org.opengis.workflow.validation.WorkflowValidationException;
import tools.jackson.databind.ObjectMapper;

class WorkflowSchemaV2Test {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void parsesAndTopologicallyValidatesV2() {
    var document = new WorkflowCodec(mapper).parse(v2("[]"));
    assertThat(document.schemaVersion()).isEqualTo(2);
    assertThat(document.nodes()).extracting("id").containsExactly("a", "b");
  }

  @Test
  void rejectsCyclesAndPythonReferences() {
    String cycle = "[{\"id\":\"e2\",\"source\":\"b\",\"target\":\"a\"}]";
    assertThatThrownBy(() -> new WorkflowCodec(mapper).parse(v2(cycle)))
        .isInstanceOf(WorkflowValidationException.class)
        .hasMessageContaining("cycle");
    assertThatThrownBy(
            () -> new WorkflowCodec(mapper).parse(v2("[]").replace("gis-build", "scripts/a.py")))
        .isInstanceOf(WorkflowValidationException.class)
        .hasMessageContaining("Python execution references");
  }

  @Test
  void migrationReportsPythonScriptsAndHooksWithoutExecutingThem() {
    String v1 =
        """
        {"schemaVersion":1,"id":"old","name":"Old","nodes":[
          {"id":"a","title":"A","scriptPath":"scripts/a.py","hooks":[{"expression":"open('x')"}]}
        ],"edges":[]}
        """;
    var report = new WorkflowMigrationService(mapper).inspect(v1);
    assertThat(report.status()).isEqualTo("manual_required");
    assertThat(report.issues())
        .extracting("code")
        .contains("python_script_reference", "python_hook");
    assertThat(report.convertedWorkflow().path("schemaVersion").asInt()).isEqualTo(2);
  }

  @Test
  void safeConditionsResolveOnlyDeclaredData() throws Exception {
    var expression =
        mapper.readTree(
            "{\"and\":[{\">\":[{\"var\":\"output.count\"},0]},{\"==\":[{\"var\":\"status\"},\"ok\"]}]}");
    var variables =
        Map.of("output", mapper.readTree("{\"count\":3}"), "status", mapper.valueToTree("ok"));
    assertThat(new SafeConditionEvaluator().evaluate(expression, variables)).isTrue();
    assertThatThrownBy(
            () ->
                new SafeConditionEvaluator()
                    .evaluate(mapper.readTree("{\"eval\":\"x\"}"), variables))
        .hasMessageContaining("Unsupported condition operator");
  }

  private static String v2(String additionalEdges) {
    String suffix =
        "[]".equals(additionalEdges)
            ? ""
            : "," + additionalEdges.substring(1, additionalEdges.length() - 1);
    return """
        {"schemaVersion":2,"id":"wf","name":"Workflow","nodes":[
          {"id":"a","title":"A","type":"agent_task","execution":{"kind":"agent_task","ref":"gis-build"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}},
          {"id":"b","title":"B","type":"agent_task","execution":{"kind":"agent_task","ref":"gis-build"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
        ],"edges":[{"id":"e1","source":"a","target":"b"}%s]}
        """
        .formatted(suffix);
  }
}
