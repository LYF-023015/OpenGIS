/** 文件职责：tool 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.tool.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.opengis.tool.api.ArtifactRef;
import org.opengis.tool.api.OpenGisTool;
import org.opengis.tool.api.ToolCall;
import org.opengis.tool.api.ToolException;
import org.opengis.tool.api.ToolResult;
import org.opengis.tool.api.ToolStatus;
import org.opengis.tool.context.ToolEvent;
import org.opengis.tool.context.ToolExecutionContext;
import org.opengis.tool.permission.PermissionAction;
import org.opengis.tool.permission.PermissionDecision;
import org.opengis.tool.permission.PermissionRuntime;
import org.opengis.tool.registry.JsonSchemaValidator;
import org.opengis.tool.registry.SchemaValidationException;
import org.opengis.tool.registry.ToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The single audited execution pipeline used by RPC and the future Agent loop. */
public final class ToolRuntime {
  public static final int DEFAULT_OUTPUT_LIMIT = 32_000;

  private final ToolRegistry registry;
  private final JsonSchemaValidator validator;
  private final PermissionRuntime permissions;
  private final ArtifactMaterializer artifacts;
  private final ObjectMapper objectMapper;
  private final int outputLimit;
  private final AtomicLong eventSequence = new AtomicLong();

  public ToolRuntime(
      ToolRegistry registry,
      JsonSchemaValidator validator,
      PermissionRuntime permissions,
      ArtifactMaterializer artifacts,
      ObjectMapper objectMapper) {
    this(registry, validator, permissions, artifacts, objectMapper, DEFAULT_OUTPUT_LIMIT);
  }

  public ToolRuntime(
      ToolRegistry registry,
      JsonSchemaValidator validator,
      PermissionRuntime permissions,
      ArtifactMaterializer artifacts,
      ObjectMapper objectMapper,
      int outputLimit) {
    this.registry = registry;
    this.validator = validator;
    this.permissions = permissions;
    this.artifacts = artifacts;
    this.objectMapper = objectMapper;
    this.outputLimit = Math.max(256, outputLimit);
  }

  public ToolResult execute(ToolCall call, ToolExecutionContext context) {
    OpenGisTool tool = registry.find(call.name()).orElse(null);
    if (tool == null) {
      return failure(
          call,
          context,
          ToolStatus.FAILED,
          "tool_not_found",
          "Tool not found: " + call.name(),
          null);
    }
    JsonNode arguments =
        call.arguments() == null || call.arguments().isNull()
            ? objectMapper.createObjectNode()
            : call.arguments();
    emit(call, context, "tool.started", objectMapper.valueToTree(Map.of("name", call.name())));
    try {
      context.cancellation().throwIfCancelled();
      validator.validate(tool.definition().inputSchema(), arguments);
      PermissionDecision decision = permissions.decide(tool.definition(), arguments, context);
      emit(call, context, "tool.permission_decided", objectMapper.valueToTree(decision));
      if (decision.action() == PermissionAction.DENY) {
        return failure(
            call, context, ToolStatus.REJECTED, "permission_denied", decision.reason(), null);
      }
      if (decision.action() == PermissionAction.ASK && !ask(call, context, decision)) {
        return failure(
            call,
            context,
            ToolStatus.REJECTED,
            "permission_rejected",
            "User rejected tool execution",
            null);
      }
      context.cancellation().throwIfCancelled();
      JsonNode output = tool.execute(arguments, context);
      context.cancellation().throwIfCancelled();
      ToolResult result = normalize(call, context, output);
      emit(call, context, "tool.completed", objectMapper.valueToTree(result));
      return result;
    } catch (SchemaValidationException exception) {
      return failure(
          call,
          context,
          ToolStatus.FAILED,
          "invalid_arguments",
          exception.getMessage(),
          objectMapper.valueToTree(exception.violations()));
    } catch (ToolException exception) {
      ToolStatus status =
          "tool_cancelled".equals(exception.code()) ? ToolStatus.CANCELLED : ToolStatus.FAILED;
      return failure(call, context, status, exception.code(), exception.getMessage(), null);
    } catch (RuntimeException exception) {
      return failure(
          call,
          context,
          ToolStatus.FAILED,
          "tool_execution_failed",
          exception.getMessage() == null
              ? exception.getClass().getSimpleName()
              : exception.getMessage(),
          null);
    }
  }

  private boolean ask(ToolCall call, ToolExecutionContext context, PermissionDecision permission) {
    ObjectNode params = objectMapper.createObjectNode();
    params.put("request_id", call.id());
    params.put("tool_name", call.name());
    params.put("question", "Allow agent tool call: " + call.name() + "?");
    params.put("reason", permission.reason());
    params.put("danger", true);
    params.put("timeout_seconds", 120);
    try {
      JsonNode result =
          context
              .uiRpc()
              .request("rpc.ui.ask.confirm", params, Duration.ofSeconds(120))
              .toCompletableFuture()
              .get(125, TimeUnit.SECONDS);
      return result.path("approved").asBoolean(false);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ToolException("tool_cancelled", "Approval wait was interrupted", exception);
    } catch (TimeoutException exception) {
      throw new ToolException("approval_timeout", "Approval request timed out", exception);
    } catch (java.util.concurrent.ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof CompletionException completion && completion.getCause() != null) {
        cause = completion.getCause();
      }
      throw new ToolException("approval_unavailable", "Approval UI is unavailable", cause);
    }
  }

  private ToolResult normalize(ToolCall call, ToolExecutionContext context, JsonNode output) {
    JsonNode safeOutput = output == null ? objectMapper.nullNode() : output;
    String serialized = objectMapper.writeValueAsString(safeOutput);
    if (serialized.length() <= outputLimit) {
      return ToolResult.completed(call.name(), safeOutput);
    }
    ArtifactRef artifact =
        artifacts.writeText(context, call.name(), serialized, "application/json");
    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("summary", serialized.substring(0, outputLimit) + "...(truncated)");
    summary.put("artifact_id", artifact.id());
    emit(call, context, "tool.artifact", objectMapper.valueToTree(artifact));
    return new ToolResult(
        true,
        ToolStatus.COMPLETED,
        call.name(),
        summary,
        Map.of("original_chars", serialized.length()),
        List.of(artifact),
        true,
        null);
  }

  private ToolResult failure(
      ToolCall call,
      ToolExecutionContext context,
      ToolStatus status,
      String code,
      String message,
      JsonNode details) {
    ToolResult result = ToolResult.failure(status, code, message, details);
    emit(
        call,
        context,
        status == ToolStatus.CANCELLED ? "tool.cancelled" : "tool.failed",
        objectMapper.valueToTree(result));
    return result;
  }

  private void emit(ToolCall call, ToolExecutionContext context, String type, JsonNode payload) {
    context
        .eventSink()
        .emit(
            new ToolEvent(
                eventSequence.incrementAndGet(),
                Instant.now(),
                type,
                context.runId(),
                call.id(),
                call.name(),
                payload));
  }
}
