/** 文件职责：settings 前端功能：验证对应功能的行为与边界。 */
import { describe, expect, it } from "vitest";
import type { RunDetail, RunSummary } from "@/plugins/automation/runs/model/runsStore";
import {
  formatNumberLabel,
  isDeepSeekRoute,
  runCreatedAtMs,
  summarizePromptCacheRuns,
  summarizePromptCacheUsage,
} from "../model/promptCacheMetrics";

describe("prompt-cache insights", () => {
  it("normalizes provider usage and computes cache ratios", () => {
    const summary = summarizePromptCacheUsage([
      {
        usage: {
          prompt_tokens: 100,
          completion_tokens: 20,
          prompt_cache_hit_tokens: 75,
          prompt_cache_miss_tokens: 25,
        },
        prompt_cache: { enabled: true, prompt_cache_key_sent: true },
        request: { system_prefix_hash: "stable", tool_schema_hash: "tools" },
      },
    ]);

    expect(summary.inputTokensLabel).toBe("100");
    expect(summary.totalTokensLabel).toBe("120");
    expect(summary.hitRatioLabel).toBe("75%");
    expect(summary.systemPrefixStable).toBe(true);
  });

  it("orders run points chronologically and detects unstable prefixes", () => {
    const runs: RunSummary[] = [run("new"), run("old")];
    const details: Record<string, RunDetail> = {
      new: detail("new", 20, "a"),
      old: detail("old", 10, "b"),
    };

    expect(
      summarizePromptCacheRuns(runs, details).map((point) => point.runId),
    ).toEqual(["old", "new"]);
    const combined = summarizePromptCacheUsage([
      ...details.new.llm_usage!,
      ...details.old.llm_usage!,
    ]);
    expect(combined.systemPrefixStable).toBe(false);
  });

  it("recognizes DeepSeek routes and formats display values", () => {
    expect(isDeepSeekRoute("deepseek-chat", "")).toBe(true);
    expect(isDeepSeekRoute("other", "https://api.deepseek.com")).toBe(true);
    expect(formatNumberLabel(1_250)).toBe("1.3k");
    expect(runCreatedAtMs("not-a-date")).toBe(0);
  });
});

function run(runId: string): RunSummary {
  return {
    run_id: runId,
    created_at: "2026-01-01T00:00:00Z",
    status: "completed",
    prompt: "",
  };
}

function detail(runId: string, totalTokens: number, prefix: string): RunDetail {
  return {
    ...run(runId),
    steps: [],
    llm_usage: [
      {
        usage: { total_tokens: totalTokens },
        request: { system_prefix_hash: prefix },
      },
    ],
  };
}
