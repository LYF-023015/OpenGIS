/** 文件职责：settings 前端功能：实现该文件名所对应的单一职责。 */
import type {
  RunDetail,
  RunLLMUsageRecord,
  RunSummary,
} from "@/plugins/automation/runs/model/runsStore";

export interface PromptCacheLoopPoint {
  runId: string;
  label: string;
  totalTokens: number | null;
  inputTokens: number | null;
  hitTokens: number | null;
  missTokens: number | null;
  hitRatio: number | null;
}
export function summarizePromptCacheRuns(
  runs: RunSummary[],
  details: Record<string, RunDetail | undefined>,
): PromptCacheLoopPoint[] {
  return runs
    .map((run) => {
      const metrics = collectPromptCacheMetrics(
        details[run.run_id]?.llm_usage || [],
      );
      return {
        runId: run.run_id,
        label: run.run_id.slice(0, 8),
        totalTokens: metrics.hasTotal ? metrics.totalTokens : null,
        inputTokens: metrics.hasInput ? metrics.inputTokens : null,
        hitTokens:
          metrics.hasHitMiss || metrics.hasCached
            ? metrics.hasHitMiss
              ? metrics.hitTokens
              : metrics.cachedTokens
            : null,
        missTokens: metrics.hasHitMiss ? metrics.missTokens : null,
        hitRatio: metrics.hitRatio,
      };
    })
    .filter((point) => point.totalTokens != null || point.hitTokens != null)
    .reverse();
}

export function summarizePromptCacheUsage(records: RunLLMUsageRecord[]) {
  const metrics = collectPromptCacheMetrics(records);
  return {
    turns: records?.length || 0,
    enabled: metrics.enabled,
    sent: metrics.sent,
    statusLabel: metrics.sent
      ? "sent"
      : metrics.blockReason || metrics.status || "not_sent",
    modeLabel:
      metrics.mode ||
      (metrics.sent ? "openai_prompt_cache_key" : "observe_only"),
    reasonLabel:
      metrics.blockReason ||
      metrics.note ||
      metrics.status ||
      (metrics.hasHitMiss || metrics.hasCached
        ? "provider_reported"
        : "provider_usage_not_reported"),
    totalTokensLabel: metrics.hasTotal
      ? String(Math.round(metrics.totalTokens))
      : "—",
    inputTokensLabel: metrics.hasInput
      ? String(Math.round(metrics.inputTokens))
      : "—",
    cachedTokensLabel: metrics.hasCached
      ? String(Math.round(metrics.cachedTokens))
      : "—",
    hitTokensLabel:
      metrics.hasHitMiss || metrics.hasCached
        ? String(
            Math.round(
              metrics.hasHitMiss ? metrics.hitTokens : metrics.cachedTokens,
            ),
          )
        : "—",
    missTokensLabel: metrics.hasHitMiss
      ? String(Math.round(metrics.missTokens))
      : "—",
    hitRatioLabel:
      metrics.hitRatio == null
        ? "—"
        : `${Math.round(metrics.hitRatio * 1000) / 10}%`,
    systemPrefixHashLabel: metrics.systemPrefixHash
      ? metrics.systemPrefixHash.slice(0, 12)
      : "—",
    dynamicSuffixHashLabel: metrics.dynamicSuffixHash
      ? metrics.dynamicSuffixHash.slice(0, 12)
      : "—",
    toolSchemaHashLabel: metrics.toolSchemaHash
      ? metrics.toolSchemaHash.slice(0, 12)
      : "—",
    systemPrefixStable: metrics.systemPrefixStable,
    toolSchemaStable: metrics.toolSchemaStable,
    systemPrefixHashCount: metrics.systemPrefixHashCount,
    toolSchemaHashCount: metrics.toolSchemaHashCount,
    hasSegmentHash: Boolean(
      metrics.systemPrefixHash ||
      metrics.dynamicSuffixHash ||
      metrics.toolSchemaHash,
    ),
  };
}

function collectPromptCacheMetrics(records: RunLLMUsageRecord[]) {
  const metrics = {
    totalTokens: 0,
    inputTokens: 0,
    cachedTokens: 0,
    hitTokens: 0,
    missTokens: 0,
    hasTotal: false,
    hasInput: false,
    hasCached: false,
    hasHitMiss: false,
    enabled: false,
    sent: false,
    blockReason: "",
    status: "",
    mode: "",
    note: "",
    hitRatio: null as number | null,
    systemPrefixHash: "",
    dynamicSuffixHash: "",
    toolSchemaHash: "",
    systemPrefixHashCount: 0,
    toolSchemaHashCount: 0,
    systemPrefixStable: true,
    toolSchemaStable: true,
  };
  const systemPrefixHashes = new Set<string>();
  const toolSchemaHashes = new Set<string>();

  for (const record of records || []) {
    const usage = record.usage || {};
    const telemetry = record.telemetry || {};
    const promptCache = record.prompt_cache || {};
    const request = record.request || {};
    const systemPrefixHash = String(request.system_prefix_hash || "");
    const dynamicSuffixHash = String(request.dynamic_suffix_hash || "");
    const toolSchemaHash = String(
      request.tool_schema_hash || promptCache.tool_schema_hash || "",
    );
    if (systemPrefixHash) {
      systemPrefixHashes.add(systemPrefixHash);
      metrics.systemPrefixHash = systemPrefixHash;
    }
    if (dynamicSuffixHash) metrics.dynamicSuffixHash = dynamicSuffixHash;
    if (toolSchemaHash) {
      toolSchemaHashes.add(toolSchemaHash);
      metrics.toolSchemaHash = toolSchemaHash;
    }
    metrics.enabled ||= Boolean(promptCache.enabled);
    metrics.sent ||= Boolean(promptCache.prompt_cache_key_sent);
    metrics.status = String(
      promptCache.prompt_cache_key_status || metrics.status || "",
    );
    metrics.blockReason = String(
      promptCache.prompt_cache_key_block_reason || metrics.blockReason || "",
    );
    metrics.mode = String(
      promptCache.provider_cache_mode || metrics.mode || "",
    );
    metrics.note = String(
      promptCache.provider_cache_note || metrics.note || "",
    );

    const prompt = firstNumber(
      usage.prompt_tokens,
      usage.input_tokens,
      telemetry.context_tokens,
    );
    const completion = firstNumber(
      usage.completion_tokens,
      usage.output_tokens,
    );
    const total = firstNumber(
      usage.total_tokens,
      prompt != null || completion != null
        ? Number(prompt || 0) + Number(completion || 0)
        : undefined,
    );
    if (total != null) {
      metrics.totalTokens += total;
      metrics.hasTotal = true;
    }
    if (prompt != null) {
      metrics.inputTokens += prompt;
      metrics.hasInput = true;
    }

    const cached = firstNumber(
      usage.prompt_cache_hit_tokens,
      nestedNumber(usage, ["prompt_tokens_details", "cached_tokens"]),
      nestedNumber(usage, ["input_tokens_details", "cached_tokens"]),
      nestedNumber(usage, ["input_token_details", "cache_read"]),
      usage.cache_read_input_tokens,
      usage.cached_tokens,
      promptCache.cached_tokens,
    );
    if (cached != null) {
      metrics.cachedTokens += cached;
      metrics.hasCached = true;
    }

    const hit = firstNumber(
      usage.prompt_cache_hit_tokens,
      promptCache.cached_tokens,
    );
    const miss = firstNumber(usage.prompt_cache_miss_tokens);
    if (hit != null || miss != null) {
      metrics.hitTokens += hit || 0;
      metrics.missTokens += miss || 0;
      metrics.hasHitMiss = true;
    }
  }

  const hitMissTotal = metrics.hitTokens + metrics.missTokens;
  metrics.hitRatio =
    metrics.hasHitMiss && hitMissTotal > 0
      ? Math.max(0, Math.min(1, metrics.hitTokens / hitMissTotal))
      : metrics.hasCached && metrics.hasInput && metrics.inputTokens > 0
        ? Math.max(0, Math.min(1, metrics.cachedTokens / metrics.inputTokens))
        : null;
  metrics.systemPrefixHashCount = systemPrefixHashes.size;
  metrics.toolSchemaHashCount = toolSchemaHashes.size;
  metrics.systemPrefixStable = systemPrefixHashes.size <= 1;
  metrics.toolSchemaStable = toolSchemaHashes.size <= 1;
  return metrics;
}

export function isDeepSeekRoute(modelName: string, baseURL: string) {
  const route = `${modelName || ""} ${baseURL || ""}`.toLowerCase();
  return route.includes("deepseek") || route.includes("api.deepseek.com");
}

export function runCreatedAtMs(createdAt: string) {
  const ms = Date.parse(createdAt || "");
  return Number.isFinite(ms) ? ms : 0;
}

export function formatNumberLabel(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return "—";
  if (value >= 1_000_000) return `${Math.round(value / 100_000) / 10}m`;
  if (value >= 1_000) return `${Math.round(value / 100) / 10}k`;
  return String(Math.round(value));
}

function firstNumber(...values: unknown[]) {
  for (const value of values) {
    if (value == null || value === "") continue;
    const number = Number(value);
    if (Number.isFinite(number) && number >= 0) return number;
  }
  return undefined;
}

function nestedNumber(source: Record<string, unknown>, path: string[]) {
  let current: unknown = source;
  for (const key of path) {
    if (!current || typeof current !== "object") return undefined;
    current = (current as Record<string, unknown>)[key];
  }
  return firstNumber(current);
}
