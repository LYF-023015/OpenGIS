/** 文件职责：knowledge 后端领域：验证对应功能的行为与边界。 */
package org.opengis.assistant.memory.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.assistant.memory.MemoryKind;
import org.opengis.assistant.memory.MemoryRepository;
import org.opengis.assistant.memory.MemoryScope;
import org.opengis.assistant.memory.search.HashingMemoryEmbeddingProvider;
import org.opengis.assistant.memory.search.MemorySearchEngine;

class KnowledgeExtractorTest {
  @TempDir Path workspace;

  @Test
  void extractsMarkersAutomaticFactsAndRecipesWithoutDuplicatingCandidates() {
    String answer =
        """
        FACT: roads 图层包含道路中心线数据
        项目默认坐标系为 EPSG:4326
        处理步骤：
        1. 检查输入坐标系
        2. 检查无效几何
        """;

    var records = new KnowledgeExtractor().extract(workspace, "run-1", answer);

    assertThat(records)
        .extracting(record -> record.kind())
        .contains(MemoryKind.FACT, MemoryKind.RECIPE);
    assertThat(records).allMatch(record -> record.scope() == MemoryScope.WORKSPACE);
    assertThat(records).allMatch(record -> record.metadata().containsKey("reason"));
  }

  @Test
  void extractsExplicitUserPreferenceAndRejectsSecrets() {
    List<MemoryTranscriptEntry> transcript =
        List.of(new MemoryTranscriptEntry("user", "记住：以后报告默认使用中文", ""));
    String answer = "FACT: api_key: should-not-be-stored";

    var records = extractor().extract(workspace, "run-2", "conversation", transcript, answer);

    assertThat(records)
        .filteredOn(record -> record.kind() == MemoryKind.PREFERENCE)
        .singleElement()
        .satisfies(record -> assertThat(record.scope()).isEqualTo(MemoryScope.GLOBAL));
    assertThat(records).noneMatch(record -> record.content().contains("should-not-be-stored"));
  }

  private KnowledgeExtractor extractor() {
    return new KnowledgeExtractor(
        new MemoryCandidateValidator(),
        path ->
            new MemoryRepository(
                path,
                workspace.resolve("global-memory.json"),
                new MemorySearchEngine(new HashingMemoryEmbeddingProvider())));
  }
}
