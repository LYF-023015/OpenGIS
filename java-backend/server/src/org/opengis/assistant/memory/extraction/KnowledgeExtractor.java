/** 文件职责：knowledge 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.memory.extraction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.opengis.assistant.memory.MemoryDraft;
import org.opengis.assistant.memory.MemoryKind;
import org.opengis.assistant.memory.MemoryRecord;
import org.opengis.assistant.memory.MemoryRepository;
import org.opengis.assistant.memory.MemoryScope;

/** Post-run extractor supporting explicit markers plus conservative automatic candidates. */
public final class KnowledgeExtractor {
  private static final Pattern DURABLE_FACT =
      Pattern.compile(
          "(?i).*(项目|workspace|数据集|图层|坐标系|字段|默认|输出目录|project|dataset|layer|crs).*(使用|采用|包含|位于|保存|为|是|uses|contains|located|stored).*",
          Pattern.DOTALL);
  private static final Pattern REMEMBER =
      Pattern.compile("(?i)^(请)?(记住|以后|默认|remember|always|prefer)[:：,，\\s]*(.+)$");
  private final MemoryCandidateValidator validator;
  private final java.util.function.Function<Path, MemoryRepository> repositories;

  public KnowledgeExtractor() {
    this(new MemoryCandidateValidator(), MemoryRepository::new);
  }

  public KnowledgeExtractor(MemoryCandidateValidator validator) {
    this(validator, MemoryRepository::new);
  }

  KnowledgeExtractor(
      MemoryCandidateValidator validator,
      java.util.function.Function<Path, MemoryRepository> repositories) {
    this.validator = java.util.Objects.requireNonNull(validator, "validator");
    this.repositories = java.util.Objects.requireNonNull(repositories, "repositories");
  }

  public List<MemoryRecord> extract(Path workspace, String runId, String finalAnswer) {
    return extract(workspace, runId, "", List.of(), finalAnswer);
  }

  public List<MemoryRecord> extract(
      Path workspace,
      String runId,
      String conversationId,
      List<MemoryTranscriptEntry> transcript,
      String finalAnswer) {
    if ((finalAnswer == null || finalAnswer.isBlank()) && transcript.isEmpty()) {
      return List.of();
    }
    List<MemoryCandidate> candidates = new ArrayList<>();
    explicitCandidates(finalAnswer, runId).forEach(candidates::add);
    automaticFacts(finalAnswer, runId).forEach(candidates::add);
    conversationPreferences(transcript, conversationId).forEach(candidates::add);
    automaticRecipe(finalAnswer, runId).ifPresent(candidates::add);

    MemoryRepository repository = repositories.apply(workspace);
    List<MemoryRecord> extracted = new ArrayList<>();
    Map<String, MemoryCandidate> unique = new LinkedHashMap<>();
    candidates.stream()
        .map(validator::validate)
        .flatMap(java.util.Optional::stream)
        .forEach(
            candidate ->
                unique.putIfAbsent(
                    candidate.kind()
                        + "|"
                        + candidate.scope()
                        + "|"
                        + normalize(candidate.content()),
                    candidate));
    for (MemoryCandidate candidate : unique.values()) {
      Map<String, String> metadata = new LinkedHashMap<>(candidate.metadata());
      metadata.put("extractor", "hybrid-v2");
      metadata.put("reason", candidate.reason());
      memoryKey(candidate).ifPresent(key -> metadata.put("memory_key", key));
      extracted.add(
          repository.add(
              new MemoryDraft(
                  candidate.kind(),
                  candidate.content(),
                  "run:" + runId,
                  candidate.scope(),
                  candidate.scopeId(),
                  candidate.confidence(),
                  candidate.importance(),
                  metadata)));
    }
    return List.copyOf(extracted);
  }

  private static List<MemoryCandidate> explicitCandidates(String answer, String runId) {
    if (answer == null) {
      return List.of();
    }
    List<MemoryCandidate> candidates = new ArrayList<>();
    for (String line : answer.lines().toList()) {
      for (MemoryKind kind : MemoryKind.values()) {
        String marker = kind.name() + ":";
        if (line.strip().regionMatches(true, 0, marker, 0, marker.length())) {
          String content = line.strip().substring(marker.length()).strip();
          candidates.add(
              new MemoryCandidate(
                  kind,
                  content,
                  kind == MemoryKind.PREFERENCE ? MemoryScope.GLOBAL : MemoryScope.WORKSPACE,
                  "",
                  0.98,
                  0.8,
                  "explicit-marker",
                  Map.of("run_id", runId)));
        }
      }
    }
    return candidates;
  }

  private static List<MemoryCandidate> automaticFacts(String answer, String runId) {
    if (answer == null) {
      return List.of();
    }
    return answer
        .lines()
        .map(String::strip)
        .filter(line -> line.length() >= 12 && line.length() <= 500)
        .filter(line -> DURABLE_FACT.matcher(line).matches())
        .limit(8)
        .map(
            line ->
                new MemoryCandidate(
                    MemoryKind.FACT,
                    line,
                    MemoryScope.WORKSPACE,
                    "",
                    0.78,
                    0.6,
                    "durable-fact-pattern",
                    Map.of("run_id", runId)))
        .toList();
  }

  private static List<MemoryCandidate> conversationPreferences(
      List<MemoryTranscriptEntry> transcript, String conversationId) {
    List<MemoryCandidate> values = new ArrayList<>();
    transcript.stream()
        .filter(entry -> "user".equalsIgnoreCase(entry.role()))
        .flatMap(entry -> entry.content().lines())
        .map(String::strip)
        .forEach(
            line -> {
              java.util.regex.Matcher matcher = REMEMBER.matcher(line);
              if (matcher.matches()) {
                values.add(
                    new MemoryCandidate(
                        MemoryKind.PREFERENCE,
                        matcher.group(3).strip(),
                        MemoryScope.GLOBAL,
                        "",
                        0.95,
                        0.75,
                        "explicit-user-preference",
                        Map.of("conversation_id", conversationId)));
              }
            });
    return values;
  }

  private static java.util.Optional<MemoryCandidate> automaticRecipe(String answer, String runId) {
    if (answer == null
        || !(answer.contains("步骤") || answer.toLowerCase(Locale.ROOT).contains("workflow"))) {
      return java.util.Optional.empty();
    }
    List<String> steps =
        answer
            .lines()
            .map(String::strip)
            .filter(line -> line.matches("^(\\d+[.、)]|[-*])\\s*.+"))
            .limit(12)
            .toList();
    if (steps.size() < 2) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        new MemoryCandidate(
            MemoryKind.RECIPE,
            String.join("\n", steps),
            MemoryScope.WORKSPACE,
            "",
            0.75,
            0.65,
            "multi-step-workflow",
            Map.of("run_id", runId)));
  }

  private static java.util.Optional<String> memoryKey(MemoryCandidate candidate) {
    String content = candidate.content();
    int separator = firstPositive(content.indexOf('：'), content.indexOf(':'), content.indexOf('='));
    if (separator > 2 && separator <= 80) {
      return java.util.Optional.of(
          candidate.kind().name().toLowerCase(Locale.ROOT)
              + ":"
              + normalize(content.substring(0, separator)));
    }
    return java.util.Optional.empty();
  }

  private static int firstPositive(int... values) {
    int result = Integer.MAX_VALUE;
    for (int value : values) {
      if (value >= 0) {
        result = Math.min(result, value);
      }
    }
    return result == Integer.MAX_VALUE ? -1 : result;
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }
}
