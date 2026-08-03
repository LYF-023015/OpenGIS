package org.opengis.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.workflow.execution.WorkflowEngine;
import org.opengis.workflow.execution.WorkflowEventSink;
import org.opengis.workflow.execution.WorkflowNodeRunner;
import tools.jackson.databind.ObjectMapper;

class WorkflowRecoveryScenarioTest {
  @TempDir Path workspace;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void completesDAGWithStableChildSessions() {
    var workflow = new WorkflowCodec(mapper).parse(twoNodes(1));
    List<String> sessions = new ArrayList<>();
    var result =
        new WorkflowEngine(mapper)
            .execute(
                workspace,
                workflow,
                "run-complete",
                request -> {
                  sessions.add(request.childSessionId());
                  return WorkflowNodeRunner.NodeResult.completed(
                      mapper.valueToTree(request.node().id()), "child", false);
                },
                new AtomicBoolean(),
                WorkflowEventSink.noop(),
                false);
    assertThat(result.status()).isEqualTo("completed");
    assertThat(sessions).containsExactly("run-complete:a", "run-complete:b");
  }

  @Test
  void retriesFailureAndPersistsAttemptCount() {
    var workflow = new WorkflowCodec(mapper).parse(twoNodes(2));
    AtomicInteger calls = new AtomicInteger();
    var result =
        new WorkflowEngine(mapper)
            .execute(
                workspace,
                workflow,
                "run-retry",
                request -> {
                  if (request.node().id().equals("a") && calls.incrementAndGet() == 1)
                    return WorkflowNodeRunner.NodeResult.failed("temporary", "child-a");
                  return WorkflowNodeRunner.NodeResult.completed(
                      mapper.valueToTree("ok"), "child", false);
                },
                new AtomicBoolean(),
                WorkflowEventSink.noop(),
                false);
    assertThat(result.status()).isEqualTo("completed");
    assertThat(result.nodes().get("a").attempts()).isEqualTo(2);
  }

  @Test
  void resumeSkipsCompletedNodesAndPreventsDuplicateSideEffects() {
    var workflow = new WorkflowCodec(mapper).parse(twoNodes(1));
    AtomicInteger sideEffects = new AtomicInteger();
    AtomicInteger firstCalls = new AtomicInteger();
    var first =
        new WorkflowEngine(mapper)
            .execute(
                workspace,
                workflow,
                "run-resume",
                request -> {
                  if (request.node().id().equals("a")) {
                    firstCalls.incrementAndGet();
                    sideEffects.incrementAndGet();
                    return WorkflowNodeRunner.NodeResult.completed(
                        mapper.valueToTree("artifact-a"), "child-a", true);
                  }
                  return WorkflowNodeRunner.NodeResult.failed("temporary", "child-b");
                },
                new AtomicBoolean(),
                WorkflowEventSink.noop(),
                false);
    assertThat(first.status()).isEqualTo("failed");

    var resumed =
        new WorkflowEngine(mapper)
            .execute(
                workspace,
                workflow,
                "run-resume",
                request -> {
                  if (request.node().id().equals("a")) sideEffects.incrementAndGet();
                  return WorkflowNodeRunner.NodeResult.completed(
                      mapper.valueToTree("ok"), "child", false);
                },
                new AtomicBoolean(),
                WorkflowEventSink.noop(),
                true);
    assertThat(resumed.status()).isEqualTo("completed");
    assertThat(firstCalls).hasValue(1);
    assertThat(sideEffects).hasValue(1);
  }

  @Test
  void cancelledRunStopsBeforeNextNode() {
    var workflow = new WorkflowCodec(mapper).parse(twoNodes(1));
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicInteger calls = new AtomicInteger();
    var result =
        new WorkflowEngine(mapper)
            .execute(
                workspace,
                workflow,
                "run-cancel",
                request -> {
                  calls.incrementAndGet();
                  cancelled.set(true);
                  return WorkflowNodeRunner.NodeResult.failed("cancelled", "child");
                },
                cancelled,
                WorkflowEventSink.noop(),
                false);
    assertThat(result.status()).isEqualTo("cancelled");
    assertThat(calls).hasValue(1);
  }

  private static String twoNodes(int attempts) {
    return """
        {"schemaVersion":2,"id":"wf","name":"Workflow","nodes":[
          {"id":"a","title":"A","type":"agent_task","execution":{"kind":"agent_task","ref":"gis-build"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":%d,"backoffMs":0}},
          {"id":"b","title":"B","type":"agent_task","execution":{"kind":"agent_task","ref":"gis-build"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":%d,"backoffMs":0}}
        ],"edges":[{"id":"e","source":"a","target":"b"}]}
        """
        .formatted(attempts, attempts);
  }
}
