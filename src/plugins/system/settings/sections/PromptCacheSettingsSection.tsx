/** 文件职责：settings 前端功能：实现该文件名所对应的单一职责。 */
import { Fragment } from "react";
import { ChevronDown } from "lucide-react";
import { useT } from "@/app/i18n";
import type { ModelSettings } from "../model/settingsStore";
import type {
  RunLLMUsageRecord,
  RunSummary,
} from "@/plugins/automation/runs/model/runsStore";
import { MiniStat, PromptCacheWave } from "./PromptCacheInsights";
import {
  formatNumberLabel,
  summarizePromptCacheUsage,
  type PromptCacheLoopPoint,
} from "../model/promptCacheMetrics";
import {
  SettingCheckbox,
  SettingItem,
  SettingSection,
} from "../components/SettingItem";

interface PromptCacheSettingsSectionProps {
  model: Pick<ModelSettings, "protocol" | "modelName">;
  promptCacheTestEnabled: boolean;
  onEnabledChange: (enabled: boolean) => void;
  promptCacheTestExpanded: boolean;
  onToggleExpanded: () => void;
  onClearHistory: () => void;
  visiblePromptCacheRunCount: number;
  currentRouteIsDeepSeek: boolean;
  promptCacheStats: ReturnType<typeof summarizePromptCacheUsage>;
  latestRun: RunSummary | null;
  latestRunUsage: RunLLMUsageRecord | null;
  promptCacheLoopPoints: PromptCacheLoopPoint[];
  sectionRef: (element: HTMLDivElement | null) => void;
}

export function PromptCacheSettingsSection({
  model,
  promptCacheTestEnabled,
  onEnabledChange,
  promptCacheTestExpanded,
  onToggleExpanded,
  onClearHistory,
  visiblePromptCacheRunCount,
  currentRouteIsDeepSeek,
  promptCacheStats,
  latestRun,
  latestRunUsage,
  promptCacheLoopPoints,
  sectionRef,
}: PromptCacheSettingsSectionProps) {
  const t = useT();
  return (
    <div id="section-promptCache" ref={sectionRef}>
      <SettingSection title={t.settings.promptCacheTestPanelTitle}>
        <SettingItem
          id="prompt-cache-summary"
          label={t.settings.promptCacheTestPanelTitle}
          description={t.settings.promptCacheTestPanelDesc}
        >
          <div className="space-y-2 text-sm text-text-secondary">
            <div className="flex flex-wrap items-center gap-3">
              <SettingCheckbox
                id="prompt-cache-test-enabled"
                checked={promptCacheTestEnabled}
                onChange={onEnabledChange}
                label={t.settings.promptCacheTestEnable}
              />
              <button
                type="button"
                onClick={onToggleExpanded}
                disabled={!promptCacheTestEnabled}
                className="
                            inline-flex h-7 items-center gap-1.5 rounded-md border border-border
                            bg-bg-tertiary px-2 text-xs text-text-secondary
                            hover:bg-bg-hover disabled:opacity-45 disabled:hover:bg-bg-tertiary
                          "
              >
                <ChevronDown
                  className={`h-3.5 w-3.5 transition-transform ${promptCacheTestExpanded ? "" : "-rotate-90"}`}
                />
                {promptCacheTestExpanded
                  ? t.settings.promptCacheTestCollapse
                  : t.settings.promptCacheTestExpand}
              </button>
              <button
                type="button"
                onClick={onClearHistory}
                disabled={
                  !promptCacheTestEnabled || visiblePromptCacheRunCount === 0
                }
                className="
                            inline-flex h-7 items-center rounded-md border border-border
                            bg-bg-tertiary px-2 text-xs text-text-muted
                            hover:bg-bg-hover hover:text-text-secondary disabled:opacity-45 disabled:hover:bg-bg-tertiary
                          "
              >
                {t.settings.promptCacheClearHistory}
              </button>
              <span
                className={`rounded px-1.5 py-0.5 text-[11px] ${currentRouteIsDeepSeek ? "bg-green-500/12 text-green-500" : "bg-amber-500/12 text-amber-500"}`}
              >
                {currentRouteIsDeepSeek
                  ? t.settings.promptCacheDeepSeekReady
                  : t.settings.promptCacheDeepSeekOnly}
              </span>
            </div>

            {promptCacheTestEnabled && promptCacheTestExpanded && (
              <div className="space-y-2">
                <div className="grid grid-cols-2 gap-2 max-w-[760px] md:grid-cols-3">
                  <MiniStat
                    label={t.settings.promptCacheProvider}
                    value={`${model.protocol} / ${model.modelName || "—"}`}
                  />
                  <MiniStat
                    label={t.settings.promptCacheMode}
                    value={promptCacheStats.modeLabel}
                  />
                  <MiniStat
                    label={t.settings.promptCacheTurns}
                    value={String(promptCacheStats.turns)}
                  />
                  <MiniStat
                    label={t.settings.promptCacheInputTokens}
                    value={promptCacheStats.inputTokensLabel}
                  />
                  <MiniStat
                    label={t.settings.promptCacheHitTokens}
                    value={promptCacheStats.hitTokensLabel}
                  />
                  <MiniStat
                    label={t.settings.promptCacheHitRatio}
                    value={promptCacheStats.hitRatioLabel}
                  />
                </div>

                <div className="rounded-md border border-border bg-bg-secondary/60 px-3 py-2 space-y-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs font-medium text-text-primary">
                      {t.settings.promptCacheLatestRun}
                    </span>
                    {latestRun && (
                      <span className="text-[11px] text-text-muted font-mono">
                        {latestRun.run_id.slice(0, 8)}
                      </span>
                    )}
                  </div>
                  {latestRunUsage ? (
                    <div className="space-y-1 text-xs text-text-muted/85">
                      <div className="flex flex-wrap gap-x-3 gap-y-1">
                        <span>
                          {t.settings.promptCacheTotalTokens}:{" "}
                          <span className="font-mono">
                            {promptCacheStats.totalTokensLabel}
                          </span>
                        </span>
                        <span>
                          {t.settings.promptCacheMissTokens}:{" "}
                          <span className="font-mono">
                            {promptCacheStats.missTokensLabel}
                          </span>
                        </span>
                        <span>
                          {t.settings.promptCacheKey}:{" "}
                          <span className="font-mono">
                            {latestRunUsage.prompt_cache?.cache_key || "—"}
                          </span>
                        </span>
                        <span>
                          {t.settings.promptCacheStrategy}:{" "}
                          <span className="font-mono">
                            {latestRunUsage.prompt_cache?.strategy || "—"}
                          </span>
                        </span>
                        <span>
                          {t.settings.promptCachePrefix}:{" "}
                          <span className="font-mono">
                            {latestRunUsage.prompt_cache?.prefix_hash?.slice(
                              0,
                              12,
                            ) || "—"}
                          </span>
                        </span>
                        <span>
                          {t.settings.promptCacheSections}:{" "}
                          <span className="font-mono">
                            {latestRunUsage.prompt_cache?.sections?.length ?? 0}
                          </span>
                        </span>
                        <span>
                          {t.settings.promptCacheReason}:{" "}
                          <span className="font-mono">
                            {promptCacheStats.reasonLabel}
                          </span>
                        </span>
                      </div>
                      <div className="text-[11px] text-text-muted/70">
                        {(latestRunUsage.prompt_cache?.sections || [])
                          .slice(0, 3)
                          .map((section: any) => (
                            <span
                              key={String(section.id)}
                              className="inline-block mr-2 font-mono"
                            >
                              {String(section.id)}#
                              {String(section.cache_policy || "none")}
                            </span>
                          ))}
                        {(latestRunUsage.prompt_cache?.sections?.length || 0) >
                          3 && (
                          <span className="italic">
                            +
                            {(latestRunUsage.prompt_cache?.sections?.length ||
                              0) - 3}{" "}
                            {t.runs.more}
                          </span>
                        )}
                      </div>
                      {promptCacheStats.hasSegmentHash && (
                        <div className="rounded border border-border/60 bg-bg-secondary/40 px-2 py-1.5 space-y-1">
                          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
                            <span className="text-text-muted/90">
                              {t.settings.promptCacheSegmentHash}
                            </span>
                            <span
                              className={`rounded px-1.5 py-0.5 ${promptCacheStats.systemPrefixStable ? "bg-green-500/12 text-green-500" : "bg-red-500/15 text-red-500"}`}
                            >
                              {promptCacheStats.systemPrefixStable
                                ? t.settings.promptCachePrefixStable
                                : t.settings.promptCachePrefixBroken}
                            </span>
                            <span
                              className={`rounded px-1.5 py-0.5 ${promptCacheStats.toolSchemaStable ? "bg-green-500/12 text-green-500" : "bg-amber-500/15 text-amber-500"}`}
                            >
                              {promptCacheStats.toolSchemaStable
                                ? t.settings.promptCacheToolStable
                                : t.settings.promptCacheToolChanged}
                            </span>
                          </div>
                          <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px]">
                            <span>
                              {t.settings.promptCacheSystemPrefixHash}:{" "}
                              <span className="font-mono">
                                {promptCacheStats.systemPrefixHashLabel}
                              </span>
                              {promptCacheStats.systemPrefixHashCount > 1 && (
                                <span className="text-red-500">
                                  {" "}
                                  ×{promptCacheStats.systemPrefixHashCount}
                                </span>
                              )}
                            </span>
                            <span>
                              {t.settings.promptCacheDynamicSuffixHash}:{" "}
                              <span className="font-mono">
                                {promptCacheStats.dynamicSuffixHashLabel}
                              </span>
                            </span>
                            <span>
                              {t.settings.promptCacheToolSchemaHash}:{" "}
                              <span className="font-mono">
                                {promptCacheStats.toolSchemaHashLabel}
                              </span>
                              {promptCacheStats.toolSchemaHashCount > 1 && (
                                <span className="text-amber-500">
                                  {" "}
                                  ×{promptCacheStats.toolSchemaHashCount}
                                </span>
                              )}
                            </span>
                          </div>
                        </div>
                      )}
                      <p className="text-[11px] leading-relaxed text-text-muted/75">
                        {t.settings.promptCacheDeepSeekHint}
                      </p>
                    </div>
                  ) : (
                    <div className="text-xs text-text-muted/70">
                      {latestRun
                        ? t.settings.promptCacheLoading
                        : t.settings.promptCacheNoRun}
                    </div>
                  )}
                </div>

                <div className="rounded-md border border-border bg-bg-secondary/60 px-3 py-2 space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs font-medium text-text-primary">
                      {t.settings.promptCacheAggregate}
                    </span>
                    <span className="text-[11px] text-text-muted">
                      {promptCacheLoopPoints.length}{" "}
                      {t.settings.promptCacheLoops}
                    </span>
                  </div>
                  {promptCacheLoopPoints.length > 0 ? (
                    <>
                      <PromptCacheWave
                        points={promptCacheLoopPoints}
                        totalLabel={t.settings.promptCacheTotalTokens}
                        hitLabel={t.settings.promptCacheHitTokens}
                      />
                      <div className="max-h-[168px] overflow-auto pr-1">
                        <div className="grid grid-cols-[minmax(72px,1fr)_80px_80px_70px] gap-x-2 gap-y-1 text-[11px]">
                          <span className="text-text-muted">
                            {t.settings.promptCacheLoop}
                          </span>
                          <span className="text-right text-text-muted">
                            {t.settings.promptCacheTotalTokens}
                          </span>
                          <span className="text-right text-text-muted">
                            {t.settings.promptCacheHitTokens}
                          </span>
                          <span className="text-right text-text-muted">
                            {t.settings.promptCacheHitRatio}
                          </span>
                          {promptCacheLoopPoints.map((point) => (
                            <Fragment key={point.runId}>
                              <span
                                className="truncate font-mono text-text-secondary"
                                title={point.runId}
                              >
                                {point.label}
                              </span>
                              <span className="text-right font-mono text-text-secondary">
                                {formatNumberLabel(point.totalTokens)}
                              </span>
                              <span className="text-right font-mono text-text-secondary">
                                {formatNumberLabel(point.hitTokens)}
                              </span>
                              <span className="text-right font-mono text-text-secondary">
                                {point.hitRatio == null
                                  ? "—"
                                  : `${Math.round(point.hitRatio * 1000) / 10}%`}
                              </span>
                            </Fragment>
                          ))}
                        </div>
                      </div>
                    </>
                  ) : (
                    <div className="text-xs text-text-muted/70">
                      {t.settings.promptCacheNoDisplayHistory}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        </SettingItem>
      </SettingSection>
    </div>
  );
}
