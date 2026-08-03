package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.server.workflow.WorkflowApplicationService;
import org.opengis.workflow.queue.AgentQueueService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class Phase6WorkflowRpcTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void registersQueueAndWorkflowMethodsAndPersistsV2(@TempDir Path workspace) {
    RpcMethodRegistry registry = new RpcMethodRegistry();
    new Phase6RpcMethods(
            registry, mock(AgentQueueService.class), mock(WorkflowApplicationService.class), mapper)
        .registerMethods();
    assertThat(
            java.util.List.of(
                "rpc.agent.queue.submit",
                "rpc.agent.queue.run",
                "rpc.agent.queue.get",
                "rpc.agent.queue.resume",
                "rpc.agent.queue.retry",
                "rpc.agent.queue.cancel",
                "rpc.agent.queue.process",
                "rpc.agent.queue.list",
                "rpc.workflow.inspect",
                "rpc.workflow.convert",
                "rpc.workflow.load",
                "rpc.workflow.save",
                "rpc.workflow.run",
                "rpc.workflow.get",
                "rpc.workflow.cancel"))
        .allMatch(method -> registry.find(method).isPresent());

    ObjectNode workflow =
        mapper.readValue(
            """
        {"schemaVersion":2,"id":"rpc-wf","name":"RPC Workflow","nodes":[
          {"id":"a","title":"A","type":"agent_task","execution":{"kind":"agent_task","ref":"gis-build"},"inputs":[],"outputs":[],"params":{},"conditions":[],"retryPolicy":{"maxAttempts":1,"backoffMs":0}}
        ],"edges":[]}
        """,
            ObjectNode.class);
    JsonNode saved =
        mapper.valueToTree(
            registry
                .find("rpc.workflow.save")
                .orElseThrow()
                .handle(
                    mapper
                        .createObjectNode()
                        .put("workspace_path", workspace.toString())
                        .set("workflow", workflow)));
    assertThat(saved.path("status").asText()).isEqualTo("ok");
    JsonNode loaded =
        mapper.valueToTree(
            registry
                .find("rpc.workflow.load")
                .orElseThrow()
                .handle(
                    mapper
                        .createObjectNode()
                        .put("workspace_path", workspace.toString())
                        .put("workflow_id", "rpc-wf")));
    assertThat(loaded.path("workflow").path("schemaVersion").asInt()).isEqualTo(2);
  }

  @Test
  void inspectReturnsManualRequiredForPythonV1() throws Exception {
    RpcMethodRegistry registry = new RpcMethodRegistry();
    new Phase6RpcMethods(
            registry, mock(AgentQueueService.class), mock(WorkflowApplicationService.class), mapper)
        .registerMethods();
    JsonNode source =
        mapper.readTree(
            "{\"schemaVersion\":1,\"id\":\"old\",\"name\":\"Old\",\"nodes\":[{\"id\":\"n\",\"scriptPath\":\"x.py\"}],\"edges\":[]}");
    JsonNode result =
        mapper.valueToTree(
            registry
                .find("rpc.workflow.inspect")
                .orElseThrow()
                .handle(mapper.createObjectNode().set("workflow", source)));
    assertThat(result.path("status").asText()).isEqualTo("manual_required");
  }
}
