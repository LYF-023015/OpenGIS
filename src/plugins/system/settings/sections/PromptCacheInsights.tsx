/** 文件职责：settings 前端功能：实现该文件名所对应的单一职责。 */
import type { PromptCacheLoopPoint } from "../model/promptCacheMetrics";

export function MiniStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-border bg-bg-secondary/60 px-2.5 py-2">
      <div className="text-[11px] text-text-muted uppercase tracking-wider">
        {label}
      </div>
      <div className="mt-0.5 text-sm text-text-primary truncate">{value}</div>
    </div>
  );
}

export function PromptCacheWave({
  points,
  totalLabel,
  hitLabel,
}: {
  points: PromptCacheLoopPoint[];
  totalLabel: string;
  hitLabel: string;
}) {
  const width = 520;
  const height = 96;
  const pad = 10;
  const values = points.map((point) => ({
    total: point.totalTokens || 0,
    hit: point.hitTokens || 0,
  }));
  const maxValue = Math.max(
    1,
    ...values.flatMap((value) => [value.total, value.hit]),
  );
  const xFor = (index: number) => {
    if (values.length <= 1) return width / 2;
    return pad + (index / (values.length - 1)) * (width - pad * 2);
  };
  const yFor = (value: number) =>
    height - pad - (value / maxValue) * (height - pad * 2);
  const path = (key: "total" | "hit") =>
    values
      .map(
        (value, index) =>
          `${index === 0 ? "M" : "L"} ${xFor(index).toFixed(1)} ${yFor(value[key]).toFixed(1)}`,
      )
      .join(" ");
  const totalPath = path("total");
  const hitPath = path("hit");
  const closeArea = (line: string) =>
    `${line} L ${xFor(values.length - 1).toFixed(1)} ${height - pad} L ${xFor(0).toFixed(1)} ${height - pad} Z`;

  return (
    <div className="rounded-md bg-bg-tertiary/70 px-2 py-2">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="h-[96px] w-full overflow-visible"
      >
        <defs>
          <linearGradient id="pcw-total-grad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#3b82f6" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#3b82f6" stopOpacity="0.04" />
          </linearGradient>
          <linearGradient id="pcw-hit-grad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#22c55e" stopOpacity="0.30" />
            <stop offset="100%" stopColor="#22c55e" stopOpacity="0.04" />
          </linearGradient>
        </defs>
        <path d={closeArea(totalPath)} fill="url(#pcw-total-grad)" />
        <path d={closeArea(hitPath)} fill="url(#pcw-hit-grad)" />
        <path
          d={totalPath}
          fill="none"
          stroke="#3b82f6"
          strokeWidth="2"
          vectorEffect="non-scaling-stroke"
        />
        <path
          d={hitPath}
          fill="none"
          stroke="#22c55e"
          strokeWidth="2"
          vectorEffect="non-scaling-stroke"
        />
        {values.map((value, index) => (
          <g key={points[index]?.runId || index}>
            <circle
              cx={xFor(index)}
              cy={yFor(value.total)}
              r="2.8"
              fill="#3b82f6"
              stroke="#1e3a5f"
              strokeWidth="1"
            />
            <circle
              cx={xFor(index)}
              cy={yFor(value.hit)}
              r="2.8"
              fill="#22c55e"
              stroke="#14532d"
              strokeWidth="1"
            />
          </g>
        ))}
      </svg>
      <div className="mt-1.5 flex items-center gap-4 text-[11px] text-text-muted">
        <LegendDot color="#3b82f6" label={totalLabel} />
        <LegendDot color="#22c55e" label={hitLabel} />
      </div>
    </div>
  );
}

function LegendDot({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        className="h-2.5 w-2.5 rounded-full"
        style={{ backgroundColor: color }}
      />
      {label}
    </span>
  );
}
