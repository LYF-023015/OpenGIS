/** 文件职责：agent 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RunArchiveRedactionTest {
  @TempDir Path workspace;

  @Test
  void redactsStructuredSecretsAndBearerTokensFromDurableArchive() throws Exception {
    RunArchive archive =
        RunArchive.open(
            workspace, "run-redaction", "Authorization: Bearer prompt-secret-123", "test", null);
    ObjectNode event = new ObjectMapper().createObjectNode();
    event.put("call_id", "call-1");
    event.put("status", "completed");
    event.putObject("arguments").put("password", "plain-password").put("api_key", "plain-key");
    event.put("message", "access_token=memory-secret-456");
    archive.appendToolCall(event);
    archive.close("success", "Bearer answer-secret-789", null);

    String archiveText =
        Files.readString(workspace.resolve(".opengis/runs/run-redaction/meta.json"))
            + Files.readString(workspace.resolve(".opengis/runs/run-redaction/tool_calls.jsonl"))
            + Files.readString(workspace.resolve(".opengis/runs/run-redaction/final_answer.md"));
    assertThat(archiveText)
        .contains("[REDACTED]")
        .doesNotContain(
            "prompt-secret-123",
            "plain-password",
            "plain-key",
            "memory-secret-456",
            "answer-secret-789");
  }
}
