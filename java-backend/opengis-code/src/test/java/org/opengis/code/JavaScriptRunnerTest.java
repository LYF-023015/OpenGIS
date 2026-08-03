package org.opengis.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.code.runner.JavaScriptRunner;
import org.opengis.code.runner.ScriptCallbacks;
import org.opengis.code.runner.ScriptRunRequest;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.ObjectMapper;

class JavaScriptRunnerTest {
  @TempDir Path workspace;

  @Test
  void compilesRunsCallbacksProgressAndArtifactsInChildJvm() {
    String source =
        """
        package sample;
        import java.nio.file.Files;
        import java.util.Map;
        import org.opengis.script.sdk.OpenGisScript;
        import org.opengis.script.sdk.ScriptContext;
        public final class DemoScript implements OpenGisScript {
          public Object run(ScriptContext context, Map<String,Object> params) throws Exception {
            System.out.println("hello-child");
            context.progress().emit(0.5, "half");
            Map<String,Object> tool = context.tools().call("echo", Map.of("value", params.get("value")));
            var output = context.resolve("result.txt");
            Files.writeString(output, String.valueOf(tool.get("echo")));
            context.artifacts().register(output, "text/plain", "result");
            return Map.of("answer", tool.get("echo"));
          }
        }
        """;
    var result =
        new JavaScriptRunner(new ObjectMapper())
            .run(
                new ScriptRunRequest(
                    workspace,
                    "script-test",
                    "demo",
                    "sample.DemoScript",
                    source,
                    Map.of("value", "ok"),
                    Set.of("workspace_files"),
                    java.util.List.of(),
                    true,
                    Duration.ofSeconds(15),
                    128,
                    new CancellationToken()),
                new ScriptCallbacks() {
                  @Override
                  public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
                    return Map.of("echo", arguments.get("value"));
                  }

                  @Override
                  public void mapEvent(String method, Map<String, Object> parameters) {}

                  @Override
                  public void event(String type, Map<String, Object> payload) {}
                });

    assertThat(result.status()).isEqualTo("completed");
    assertThat(result.output()).asString().contains("ok");
    assertThat(result.progress()).hasSize(1);
    assertThat(result.artifacts()).hasSize(1);
    assertThat(result.stdoutPath()).content().contains("hello-child");
    assertThat(workspace.resolve("result.txt")).content().isEqualTo("ok");
  }

  @Test
  void validatorRejectsProcessCreation() {
    var result =
        new JavaScriptRunner(new ObjectMapper())
            .validate("public class Bad { void x(){ new ProcessBuilder(); } }", "Bad", Set.of());
    assertThat(result.ok()).isFalse();
    assertThat(result.errors()).extracting("code").contains("blocked_constructor");
  }
}
