package org.opengis.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.opengis.tool.api.ToolDefinition;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolRisk;
import org.opengis.tool.context.ToolExecutionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shell execution without string concatenation or an implicit secondary shell. */
final class ShellTool extends FunctionalTool {
  ShellTool(ObjectMapper mapper) {
    super(definition(mapper), ShellTool::executeProcess);
  }

  private static ToolDefinition definition(ObjectMapper mapper) {
    JsonNode schema =
        ToolSchemas.object(
            mapper,
            Map.of(
                "argv", ToolSchemas.array(mapper, ToolSchemas.string(mapper), 1),
                "workdir", ToolSchemas.optionalString(mapper),
                "timeout", ToolSchemas.integer(mapper, 1, 600_000),
                "description", ToolSchemas.string(mapper)),
            "argv",
            "description");
    return new ToolDefinition(
        "bash",
        "Execute Shell Command",
        "Execute an argv array directly; command-string parsing is intentionally unsupported.",
        "system",
        "core",
        "2.0.0",
        ToolRisk.PROCESS,
        schema,
        List.of("process", "argv"));
  }

  private static JsonNode executeProcess(JsonNode args, ToolExecutionContext context) {
    List<String> argv = args.path("argv").valueStream().map(JsonNode::asString).toList();
    Path workdir = WorkspacePaths.resolve(context, args.path("workdir").asString("."));
    long timeoutMillis = args.path("timeout").asLong(Duration.ofMinutes(10).toMillis());
    Process process = null;
    try {
      process =
          new ProcessBuilder(argv).directory(workdir.toFile()).redirectErrorStream(false).start();
      Process running = process;
      try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
        var stdoutFuture = readers.submit(() -> running.getInputStream().readAllBytes());
        var stderrFuture = readers.submit(() -> running.getErrorStream().readAllBytes());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (process.isAlive() && System.nanoTime() < deadline) {
          context.cancellation().throwIfCancelled();
          process.waitFor(50, TimeUnit.MILLISECONDS);
        }
        if (process.isAlive()) {
          destroyTree(process);
          throw new ToolException(
              "process_timeout", "Command timed out after " + timeoutMillis + "ms");
        }
        String stdout = new String(stdoutFuture.get(), StandardCharsets.UTF_8);
        String stderr = new String(stderrFuture.get(), StandardCharsets.UTF_8);
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.put("exit_code", process.exitValue());
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("success", process.exitValue() == 0);
        return result;
      }
    } catch (ToolException exception) {
      if (process != null && process.isAlive()) {
        destroyTree(process);
      }
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (process != null) {
        destroyTree(process);
      }
      throw new ToolException("tool_cancelled", "Shell execution interrupted", exception);
    } catch (ExecutionException exception) {
      throw new ToolException("process_output_failed", "Cannot capture process output", exception);
    } catch (IOException exception) {
      throw new ToolException("process_start_failed", "Cannot start command", exception);
    }
  }

  private static void destroyTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }
}
