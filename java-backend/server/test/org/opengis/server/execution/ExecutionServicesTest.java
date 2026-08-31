/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.tool.api.UiRpcPort;
import org.opengis.tool.context.CancellationToken;
import org.opengis.tool.context.ToolEventSink;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import tools.jackson.databind.ObjectMapper;

class ExecutionServicesTest {
  @Test
  void toolCatalogExposesAllOperationCodeAndWorkerLifecycleNames() {
    ObjectMapper mapper = new ObjectMapper();
    ScriptExecutionBridge bridge = new ScriptExecutionBridge(mapper);
    ExecutionServices services = new ExecutionServices(mapper, bridge);
    try {
      assertThat(
              ExecutionToolCatalog.create(mapper, services, bridge).stream()
                  .map(tool -> tool.definition().name()))
          .contains(
              "list_operations",
              "get_operation",
              "copy_operation_to_workspace",
              "create_operation",
              "edit_operation",
              "validate_operation",
              "run_operation",
              "promote_script_to_operation",
              "execute_code",
              "start_worker",
              "start_dynamic_map_worker",
              "get_worker",
              "wait_worker_update",
              "restart_worker",
              "list_workers",
              "pause_worker",
              "delete_worker");
    } finally {
      services.close();
    }
  }

  @Test
  void directJavaCodePathCompilesRunsArchivesAndReturnsLegacyShape(@TempDir Path workspace) {
    ObjectMapper mapper = new ObjectMapper();
    ScriptExecutionBridge bridge = new ScriptExecutionBridge(mapper);
    ExecutionServices services = new ExecutionServices(mapper, bridge);
    try {
      var arguments = mapper.createObjectNode();
      arguments.put("run_id", "phase8-server-script");
      arguments.put(
          "code",
          """
          package demo;
          import java.util.Map;
          import org.opengis.script.sdk.OpenGisScript;
          import org.opengis.script.sdk.ScriptContext;
          public final class Hello implements OpenGisScript {
            public Object run(ScriptContext context, Map<String,Object> params) {
              System.out.println("phase8");
              context.progress().emit(1.0, "done");
              return Map.of("answer", 8);
            }
          }
          """);
      var result = services.runScript(context(workspace), arguments);

      assertThat(result.path("ok").asBoolean()).isTrue();
      assertThat(result.path("output").path("answer").asInt()).isEqualTo(8);
      assertThat(result.path("duration_ms").asLong()).isNotNegative();
      assertThat(workspace.resolve(".opengis/script-runs/phase8-server-script/run.json"))
          .isRegularFile();
      assertThat(services.listScripts(workspace, "java-script", 10).path("scripts")).hasSize(1);
    } finally {
      services.close();
    }
  }

  private static ToolExecutionContext context(Path workspace) {
    return new ToolExecutionContext(
        workspace,
        "direct",
        "test",
        "gis-build",
        Map.of(),
        PermissionAction.ALLOW,
        new CancellationToken(),
        ToolEventSink.noop(),
        UiRpcPort.disconnected());
  }
}
