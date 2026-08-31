/** 文件职责：knowledge 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.assistant.memory.consolidation.MemoryConsolidationPolicy;
import org.opengis.assistant.memory.consolidation.MemoryConsolidator;
import org.opengis.assistant.memory.search.HashingMemoryEmbeddingProvider;
import org.opengis.assistant.memory.search.MemorySearchEngine;
import org.opengis.assistant.memory.search.MemorySearchQuery;

class MemoryRepositoryTest {
  @TempDir Path workspace;

  @Test
  void deduplicatesIdenticalMemoryAndSupersedesConflictingKeys() {
    MemoryRepository repository = repository();
    MemoryDraft first = draft("项目默认坐标系为 EPSG:4326", Map.of("memory_key", "project-crs"));
    MemoryRecord created = repository.add(first);
    MemoryRecord duplicate = repository.add(first);
    MemoryRecord replacement =
        repository.add(draft("项目默认坐标系为 EPSG:3857", Map.of("memory_key", "project-crs")));

    assertThat(duplicate.id()).isEqualTo(created.id());
    assertThat(repository.find(created.id()).orElseThrow().status())
        .isEqualTo(MemoryStatus.SUPERSEDED);
    assertThat(replacement.status()).isEqualTo(MemoryStatus.ACTIVE);
    assertThat(repository.list()).hasSize(2);
  }

  @Test
  void hybridSearchFiltersIrrelevantAndHonorsConversationScopeAndBudget() {
    MemoryRepository repository = repository();
    repository.add(draft("项目坐标系使用 EPSG:4326", Map.of()));
    repository.add(draft("咖啡机位于办公室", Map.of()));
    repository.add(
        new MemoryDraft(
            MemoryKind.FACT,
            "当前会话的私有图层名称是 roads",
            "test",
            MemoryScope.CONVERSATION,
            "conversation-a",
            0.9,
            0.8,
            Map.of()));

    var project =
        repository.search(
            new MemorySearchQuery("EPSG 坐标系", 8, 100, Set.of(MemoryScope.WORKSPACE), "", ""));
    var hiddenConversation =
        repository.search(
            new MemorySearchQuery(
                "roads 私有图层", 8, 100, Set.of(MemoryScope.CONVERSATION), "conversation-b", ""));

    assertThat(project)
        .extracting(result -> result.record().content())
        .containsExactly("项目坐标系使用 EPSG:4326");
    assertThat(hiddenConversation).isEmpty();
  }

  @Test
  void supportsCrudAndConservativeCapacityConsolidation() {
    MemoryRepository repository = repository();
    MemoryRecord low =
        repository.add(
            new MemoryDraft(
                MemoryKind.FACT,
                "低优先级项目事实记录",
                "test",
                MemoryScope.WORKSPACE,
                "",
                0.8,
                0.2,
                Map.of()));
    MemoryRecord high =
        repository.add(
            new MemoryDraft(
                MemoryKind.FACT,
                "高优先级项目事实记录",
                "test",
                MemoryScope.WORKSPACE,
                "",
                0.9,
                0.9,
                Map.of()));
    MemoryRecord updated =
        repository
            .update(
                high.id(),
                new MemoryUpdate("已修正的高优先级项目事实", 0.95, null, null, Map.of("owner", "qa")))
            .orElseThrow();
    var report =
        new MemoryConsolidator(repository)
            .consolidate(new MemoryConsolidationPolicy(Duration.ofDays(365), 0.0, 1));

    assertThat(updated.content()).contains("已修正");
    assertThat(updated.metadata()).containsEntry("owner", "qa");
    assertThat(report.overflowArchived()).isEqualTo(1);
    assertThat(repository.find(low.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ARCHIVED);
    assertThat(repository.delete(high.id())).isTrue();
    assertThat(repository.find(high.id())).isEmpty();
  }

  @Test
  void redactsSecretsBeforePersistingMemory() throws Exception {
    MemoryRepository repository = repository();
    MemoryRecord record =
        repository.add(
            draft(
                "API_KEY=memory-secret-value and Bearer bearer-secret-value",
                Map.of("access_token", "metadata-secret-value")));

    assertThat(record.content()).contains("[REDACTED]").doesNotContain("memory-secret-value");
    assertThat(record.metadata()).containsEntry("access_token", "[REDACTED]");
    assertThat(Files.readString(workspace.resolve(".opengis/memory/records.json")))
        .doesNotContain("memory-secret-value", "metadata-secret-value", "bearer-secret-value");
  }

  private MemoryRepository repository() {
    return new MemoryRepository(
        workspace,
        workspace.resolve("global-memory.json"),
        new MemorySearchEngine(new HashingMemoryEmbeddingProvider()));
  }

  private static MemoryDraft draft(String content, Map<String, String> metadata) {
    return new MemoryDraft(
        MemoryKind.FACT, content, "test", MemoryScope.WORKSPACE, "", 0.9, 0.7, metadata);
  }
}
