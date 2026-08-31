/** 文件职责：layers 前端功能：实现该文件名所对应的单一职责。 */
import { useEffect, useState } from "react";
import type {
  MapLayerDefinition,
  RasterColorRampName,
  RasterColorStop,
  RasterStyleSettings,
} from "@/shared/geo";
import {
  ColorSwatch,
  StyleRow,
  normaliseHex,
} from "../style/styleControls";

const RASTER_RAMPS: RasterColorRampName[] = [
  "viridis",
  "magma",
  "plasma",
  "inferno",
  "turbo",
  "gray",
  "terrain",
  "spectral",
  "custom",
];

const DEFAULT_CUSTOM_STOPS: RasterColorStop[] = [
  { value: 0, color: "#2b83ba", opacity: 0.15 },
  { value: 0.5, color: "#ffffbf", opacity: 0.75 },
  { value: 1, color: "#d7191c", opacity: 1 },
];

const RAMP_PREVIEWS: Record<
  Exclude<RasterColorRampName, "custom">,
  string[]
> = {
  viridis: ["#440154", "#31688e", "#35b779", "#fde725"],
  magma: ["#000004", "#711f81", "#f0605d", "#fcfdbf"],
  plasma: ["#0d0887", "#9c179e", "#ed7953", "#f0f921"],
  inferno: ["#000004", "#781c6d", "#ed6925", "#fcffa4"],
  turbo: ["#30123b", "#1ae4b6", "#faba39", "#7a0403"],
  gray: ["#111827", "#6b7280", "#f9fafb"],
  terrain: ["#286f4e", "#c8b568", "#f2f2e8", "#6b7280"],
  spectral: ["#9e0142", "#fdae61", "#ffffbf", "#66c2a5", "#5e4fa2"],
};

interface RasterStyleEditorProps {
  layer: MapLayerDefinition;
  rasterStyle: RasterStyleSettings;
  disabled: boolean;
  onApply: (raster: RasterStyleSettings) => void;
}

export function RasterStyleEditor({
  layer,
  rasterStyle,
  disabled,
  onApply,
}: RasterStyleEditorProps) {
  const [draft, setDraft] = useState<RasterStyleSettings>(() =>
    normalizeRasterDraft(rasterStyle, layer),
  );

  useEffect(() => {
    setDraft(normalizeRasterDraft(rasterStyle, layer));
  }, [layer, rasterStyle]);

  const bandCount = layer.data.kind === "raster" ? layer.data.bandCount : 1;
  const isCustom = draft.ramp === "custom";
  const stops = normalizeStops(draft.stops);
  const previewStops = isCustom ? stops : undefined;
  const bandIndex = Math.max(
    0,
    Math.min(
      (draft.band ?? 1) - 1,
      (layer.data.kind === "raster" ? layer.data.bandStats?.length : 0) ?? 0,
    ),
  );
  const stats =
    layer.data.kind === "raster"
      ? layer.data.bandStats?.[bandIndex]
      : undefined;

  const commit = (patch: RasterStyleSettings) => {
    const next = normalizeRasterDraft({ ...draft, ...patch }, layer);
    setDraft(next);
    onApply(next);
  };
  const setStop = (index: number, patch: Partial<RasterColorStop>) => {
    const nextStops = stops.map((stop, current) =>
      current === index ? { ...stop, ...patch } : stop,
    );
    setDraft({
      ...draft,
      ramp: "custom",
      stops: sortStops(nextStops),
      stopsUnit: "normalized",
    });
  };

  return (
    <div className="space-y-1.5">
      <StyleRow label="色带">
        <select
          value={draft.ramp ?? "viridis"}
          disabled={disabled}
          onChange={(event) => {
            const ramp = event.target.value as RasterColorRampName;
            const next = normalizeRasterDraft(
              {
                ...draft,
                ramp,
                stops: ramp === "custom" ? stops : undefined,
                stopsUnit: ramp === "custom" ? "normalized" : undefined,
              },
              layer,
            );
            setDraft(next);
            onApply(next);
          }}
          className="flex-1 min-w-0 bg-bg-secondary border border-border rounded px-2 py-1 text-2xs text-text-primary disabled:opacity-50"
        >
          {RASTER_RAMPS.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </StyleRow>

      <div
        className="h-4 rounded border border-border/70 overflow-hidden"
        style={{
          background: rasterRampGradient(draft.ramp ?? "viridis", previewStops),
        }}
      />
      {disabled && (
        <div className="rounded bg-bg-secondary/70 px-2 py-1 text-2xs text-text-muted">
          当前栅格缺少可重渲染来源。请重新从文件加载 TIFF，或用 agent 以
          tiles/frontend 模式加载。
        </div>
      )}

      <StyleRow label="波段">
        <input
          type="number"
          min={1}
          max={bandCount}
          value={draft.band ?? 1}
          disabled={disabled}
          onChange={(event) =>
            commit({
              band: parseInt(event.target.value || "1", 10),
              mode: "singleband",
            })
          }
          className="w-16 bg-bg-secondary border border-border rounded px-2 py-1 text-2xs text-text-primary disabled:opacity-50"
        />
        <span className="text-2xs text-text-muted">/ {bandCount}</span>
        <label className="ml-auto flex items-center gap-1 text-2xs text-text-muted">
          <input
            type="checkbox"
            checked={!!draft.reverse}
            disabled={disabled}
            onChange={(event) => commit({ reverse: event.target.checked })}
            className="accent-accent-primary"
          />
          反转
        </label>
      </StyleRow>

      <div className="grid grid-cols-2 gap-1.5">
        <NumberField
          label="Min"
          value={draft.min}
          placeholder={stats?.min?.toPrecision(4) ?? "auto"}
          disabled={disabled}
          onCommit={(min) => commit({ min })}
        />
        <NumberField
          label="Max"
          value={draft.max}
          placeholder={stats?.max?.toPrecision(4) ?? "auto"}
          disabled={disabled}
          onCommit={(max) => commit({ max })}
        />
      </div>

      {isCustom && (
        <div className="rounded-md bg-bg-secondary/70 p-1.5 space-y-1.5">
          <div className="flex items-center justify-between">
            <span className="text-2xs text-text-muted">自定义 stop</span>
            <button
              disabled={disabled || stops.length >= 12}
              onClick={() =>
                setDraft({
                  ...draft,
                  ramp: "custom",
                  stops: sortStops([
                    ...stops,
                    { value: 0.5, color: "#ffffff", opacity: 1 },
                  ]),
                  stopsUnit: "normalized",
                })
              }
              className="px-1.5 py-0.5 rounded text-2xs text-accent-primary hover:bg-accent-primary/10 disabled:opacity-40"
            >
              添加
            </button>
          </div>
          {stops.map((stop, index) => (
            <div
              key={index}
              className="grid grid-cols-[44px_24px_1fr_22px] items-center gap-1"
            >
              <input
                type="number"
                min={0}
                max={1}
                step={0.01}
                value={Number(stop.value.toFixed(2))}
                disabled={disabled}
                onChange={(event) =>
                  setStop(index, {
                    value: clamp01(parseFloat(event.target.value || "0")),
                  })
                }
                className="bg-bg-tertiary border border-border rounded px-1 py-0.5 text-2xs text-text-primary"
              />
              <ColorSwatch
                color={stop.color}
                onChange={(color) => setStop(index, { color })}
              />
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={stop.opacity ?? 1}
                disabled={disabled}
                onChange={(event) =>
                  setStop(index, {
                    opacity: clamp01(parseFloat(event.target.value)),
                  })
                }
                className="h-1 accent-accent-primary"
              />
              <button
                disabled={disabled || stops.length <= 2}
                onClick={() =>
                  setDraft({
                    ...draft,
                    ramp: "custom",
                    stops: stops.filter((_, current) => current !== index),
                    stopsUnit: "normalized",
                  })
                }
                className="w-5 h-5 rounded text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 disabled:opacity-30"
              >
                ×
              </button>
            </div>
          ))}
          <button
            disabled={disabled}
            onClick={() =>
              onApply(
                normalizeRasterDraft(
                  { ...draft, ramp: "custom", stops, stopsUnit: "normalized" },
                  layer,
                ),
              )
            }
            className="w-full rounded bg-accent-primary/10 hover:bg-accent-primary/20 text-accent-primary text-2xs font-medium py-1 disabled:opacity-40"
          >
            应用自定义色带
          </button>
        </div>
      )}
    </div>
  );
}

function NumberField({
  label,
  value,
  placeholder,
  disabled,
  onCommit,
}: {
  label: string;
  value?: number;
  placeholder?: string;
  disabled?: boolean;
  onCommit: (value: number | undefined) => void;
}) {
  const [local, setLocal] = useState(value === undefined ? "" : String(value));
  useEffect(() => setLocal(value === undefined ? "" : String(value)), [value]);
  return (
    <label className="flex items-center gap-1 text-2xs text-text-muted">
      <span className="w-7">{label}</span>
      <input
        value={local}
        disabled={disabled}
        placeholder={placeholder}
        onChange={(event) => setLocal(event.target.value)}
        onBlur={() => {
          const trimmed = local.trim();
          if (!trimmed) onCommit(undefined);
          else {
            const parsed = Number(trimmed);
            if (Number.isFinite(parsed)) onCommit(parsed);
          }
        }}
        onKeyDown={(event) => {
          if (event.key === "Enter") (event.target as HTMLInputElement).blur();
        }}
        className="min-w-0 flex-1 bg-bg-secondary border border-border rounded px-1.5 py-0.5 text-2xs text-text-primary disabled:opacity-50"
      />
    </label>
  );
}

function normalizeRasterDraft(
  style: RasterStyleSettings,
  layer: MapLayerDefinition,
): RasterStyleSettings {
  const ramp = style.ramp ?? (style.stops?.length ? "custom" : "viridis");
  const stretch = rasterStyleStretch(style, layer);
  return {
    ...style,
    ramp,
    band: Math.max(1, Math.round(style.band ?? 1)),
    stops:
      ramp === "custom"
        ? normalizeStops(style.stops, style.stopsUnit, stretch)
        : style.stops,
    stopsUnit: ramp === "custom" ? "normalized" : undefined,
  };
}

function normalizeStops(
  stops?: RasterColorStop[],
  stopsUnit?: RasterStyleSettings["stopsUnit"],
  stretch?: { min: number; max: number },
): RasterColorStop[] {
  const source = stops && stops.length >= 2 ? stops : DEFAULT_CUSTOM_STOPS;
  const range = stretch ? stretch.max - stretch.min || 1 : 1;
  return sortStops(
    source.map((stop) => ({
      value: clamp01(
        stopsUnit === "source" && stretch
          ? (Number(stop.value) - stretch.min) / range
          : Number(stop.value),
      ),
      color: normaliseHex(stop.color),
      opacity: clamp01(stop.opacity ?? 1),
    })),
  );
}

function rasterStyleStretch(
  style: RasterStyleSettings,
  layer: MapLayerDefinition,
): { min: number; max: number } | undefined {
  if (layer.data.kind !== "raster") return undefined;
  const bandIndex = Math.max(
    0,
    Math.min((style.band ?? 1) - 1, (layer.data.bandStats?.length ?? 1) - 1),
  );
  const stats = layer.data.bandStats?.[bandIndex];
  const min = style.min ?? stats?.p2 ?? stats?.min;
  const max = style.max ?? stats?.p98 ?? stats?.max;
  return typeof min === "number" &&
    typeof max === "number" &&
    Number.isFinite(min) &&
    Number.isFinite(max) &&
    min !== max
    ? { min, max }
    : undefined;
}

const sortStops = (stops: RasterColorStop[]) =>
  [...stops].sort((a, b) => a.value - b.value);
const clamp01 = (value: number) =>
  Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;

function rasterRampGradient(
  ramp: RasterColorRampName,
  stops?: RasterColorStop[],
): string {
  const colors =
    ramp === "custom"
      ? normalizeStops(stops).map(
          (stop) =>
            `${hexToRgba(stop.color, stop.opacity ?? 1)} ${Math.round(stop.value * 100)}%`,
        )
      : (RAMP_PREVIEWS[ramp] ?? RAMP_PREVIEWS.viridis).map(
          (color, index, values) =>
            `${color} ${Math.round((index / Math.max(1, values.length - 1)) * 100)}%`,
        );
  return `linear-gradient(90deg, ${colors.join(", ")})`;
}

function hexToRgba(color: string, opacity: number): string {
  const hex = normaliseHex(color).slice(1);
  return `rgba(${parseInt(hex.slice(0, 2), 16)}, ${parseInt(hex.slice(2, 4), 16)}, ${parseInt(hex.slice(4, 6), 16)}, ${clamp01(opacity)})`;
}
