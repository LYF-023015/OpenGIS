package org.opengis.server.phase8;

import java.util.Map;
import java.util.UUID;
import org.opengis.code.runner.ScriptCallbacks;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolResult;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.runtime.ToolRuntime;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Connects child JVM callbacks to the governed ToolRuntime and the current UI connection. */
@Component
public final class Phase8ExecutionBridge {
  private final ObjectMapper mapper;
  private volatile ToolRuntime tools;

  public Phase8ExecutionBridge(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void bind(ToolRuntime runtime) {
    if (tools != null && tools != runtime)
      throw new IllegalStateException("ToolRuntime already bound");
    tools = runtime;
  }

  public ScriptCallbacks callbacks(ToolExecutionContext context) {
    return new ScriptCallbacks() {
      @Override
      public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
        ToolRuntime runtime = tools;
        if (runtime == null) throw new IllegalStateException("ToolRuntime is not ready");
        ToolResult result =
            runtime.execute(
                new ToolCall(UUID.randomUUID().toString(), name, mapper.valueToTree(arguments)),
                context);
        if (!result.success()) {
          throw new IllegalStateException(
              result.error() == null ? result.status().name() : result.error().message());
        }
        return result.output() == null ? Map.of() : mapper.convertValue(result.output(), Map.class);
      }

      @Override
      public void mapEvent(String method, Map<String, Object> parameters) {
        safeNotify(context, method, mapper.valueToTree(parameters));
      }

      @Override
      public void event(String type, Map<String, Object> payload) {
        if ("artifact_registered".equals(type)
            && String.valueOf(payload.getOrDefault("mime_type", "")).startsWith("image/")) {
          safeNotify(
              context,
              "rpc.ui.chat.show_image",
              mapper.valueToTree(
                  Map.of(
                      "path", String.valueOf(payload.get("absolute_path")),
                      "caption", String.valueOf(payload.getOrDefault("title", "Java plot")),
                      "run_id", context.runId())));
        }
        var value = mapper.createObjectNode();
        value.put("run_id", context.runId());
        value.setAll((tools.jackson.databind.node.ObjectNode) mapper.valueToTree(payload));
        String method =
            switch (type) {
              case "started" -> "rpc.code.script_started";
              case "stdout" -> "rpc.code.stdout";
              case "stderr" -> "rpc.code.stderr";
              case "completed", "failed", "cancelled" -> "rpc.code.script_done";
              default -> "rpc.code.script_event";
            };
        value.put("event", type);
        safeNotify(context, method, value);
      }
    };
  }

  private static void safeNotify(
      ToolExecutionContext context, String method, tools.jackson.databind.JsonNode parameters) {
    try {
      context.uiRpc().notify(method, parameters);
    } catch (RuntimeException ignored) {
      // RPC responses and durable logs remain authoritative when no Renderer is connected.
    }
  }
}
