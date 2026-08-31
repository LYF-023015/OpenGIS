/** 文件职责：layers 前端功能：定义领域数据结构与协议。 */
import type {
  ClassificationMethod,
  FieldDescriptor,
  GeoJSONFeatureCollection,
  MapLayerDefinition,
  NumericVisualVariable,
  SortVisualVariable,
} from "@/shared/geo";
// ─── Color Ramp Presets ─────────────────────────────────────────

export interface ColorRamp {
  id: string;
  name: string;
  colors: (n: number) => string[];
}

/** Interpolate between two hex colors */
function lerpColor(a: string, b: string, t: number): string {
  const parse = (hex: string) => {
    const h = hex.replace("#", "");
    return [
      parseInt(h.slice(0, 2), 16),
      parseInt(h.slice(2, 4), 16),
      parseInt(h.slice(4, 6), 16),
    ];
  };
  const [r1, g1, b1] = parse(a);
  const [r2, g2, b2] = parse(b);
  const r = Math.round(r1 + (r2 - r1) * t);
  const g = Math.round(g1 + (g2 - g1) * t);
  const bl = Math.round(b1 + (b2 - b1) * t);
  return `#${r.toString(16).padStart(2, "0")}${g.toString(16).padStart(2, "0")}${bl.toString(16).padStart(2, "0")}`;
}

/** Generate n colors by interpolating through a list of stops */
function interpolateRamp(stops: string[], n: number): string[] {
  if (n <= 1) return [stops[0]];
  if (n === stops.length) return [...stops];
  const out: string[] = [];
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    const pos = t * (stops.length - 1);
    const lo = Math.floor(pos);
    const hi = Math.min(lo + 1, stops.length - 1);
    const frac = pos - lo;
    out.push(lerpColor(stops[lo], stops[hi], frac));
  }
  return out;
}

export const COLOR_RAMPS: ColorRamp[] = [
  {
    id: "viridis",
    name: "Viridis",
    colors: (n) =>
      interpolateRamp(
        ["#440154", "#3b528b", "#21918c", "#5ec962", "#fde725"],
        n,
      ),
  },
  {
    id: "plasma",
    name: "Plasma",
    colors: (n) =>
      interpolateRamp(
        ["#0d0887", "#7e03a8", "#cc4778", "#f89540", "#f0f921"],
        n,
      ),
  },
  {
    id: "inferno",
    name: "Inferno",
    colors: (n) =>
      interpolateRamp(
        ["#000004", "#420a68", "#932667", "#dd513a", "#fcffa4"],
        n,
      ),
  },
  {
    id: "magma",
    name: "Magma",
    colors: (n) =>
      interpolateRamp(
        ["#000004", "#3b0f70", "#8c2981", "#de4968", "#fcfdbf"],
        n,
      ),
  },
  {
    id: "reds",
    name: "Reds",
    colors: (n) => interpolateRamp(["#fff5f0", "#fb6a4a", "#a50f15"], n),
  },
  {
    id: "blues",
    name: "Blues",
    colors: (n) => interpolateRamp(["#f7fbff", "#6baed6", "#08306b"], n),
  },
  {
    id: "greens",
    name: "Greens",
    colors: (n) => interpolateRamp(["#f7fcf5", "#74c476", "#00441b"], n),
  },
  {
    id: "oranges",
    name: "Oranges",
    colors: (n) => interpolateRamp(["#fff5eb", "#fd8d3c", "#7f2704"], n),
  },
  {
    id: "rdylgn",
    name: "Red-Yellow-Green",
    colors: (n) => interpolateRamp(["#d73027", "#fee08b", "#1a9850"], n),
  },
  {
    id: "rdylbu",
    name: "Red-Yellow-Blue",
    colors: (n) => interpolateRamp(["#d73027", "#fee090", "#4575b4"], n),
  },
  {
    id: "spectral",
    name: "Spectral",
    colors: (n) =>
      interpolateRamp(
        ["#9e0142", "#f46d43", "#fee08b", "#abdda4", "#5e4fa2"],
        n,
      ),
  },
  {
    id: "tableau10",
    name: "Tableau 10",
    colors: (n) => {
      const base = [
        "#4e79a7",
        "#f28e2b",
        "#e15759",
        "#76b7b2",
        "#59a14f",
        "#edc949",
        "#af7aa1",
        "#ff9da7",
        "#9c755f",
        "#bab0ab",
      ];
      const out: string[] = [];
      for (let i = 0; i < n; i++) out.push(base[i % base.length]);
      return out;
    },
  },
];

// ─── Helpers ────────────────────────────────────────────────────

export function getNumericFields(layer: MapLayerDefinition): FieldDescriptor[] {
  if (layer.data.kind !== "vector") return [];
  return layer.data.fields.filter((f) => f.type === "number");
}

export function getAllFields(layer: MapLayerDefinition): FieldDescriptor[] {
  if (layer.data.kind !== "vector") return [];
  return layer.data.fields;
}

export function sampleNumericValues(
  layer: MapLayerDefinition,
  field: string,
): number[] {
  if (layer.data.kind !== "vector") return [];
  const fc = layer.data.geojson as GeoJSONFeatureCollection;
  const out: number[] = [];
  for (const f of fc.features) {
    const v = (f.properties ?? {})[field];
    const n = typeof v === "number" ? v : Number(v);
    if (Number.isFinite(n)) out.push(n);
  }
  return out.sort((a, b) => a - b);
}

export function sampleUniqueValues(
  layer: MapLayerDefinition,
  field: string,
): string[] {
  if (layer.data.kind !== "vector") return [];
  const fc = layer.data.geojson as GeoJSONFeatureCollection;
  const counts = new Map<string, number>();
  for (const f of fc.features) {
    const raw = (f.properties ?? {})[field];
    if (raw === null || raw === undefined) continue;
    const key = String(raw);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([k]) => k);
}

export function quantileBreaks(sorted: number[], classes: number): number[] {
  const breaks: number[] = [];
  for (let i = 1; i < classes; i++) {
    const idx = Math.floor((i / classes) * sorted.length);
    breaks.push(sorted[Math.min(idx, sorted.length - 1)]);
  }
  return stabilizeBreaks(sorted, breaks, classes);
}

export function equalIntervalBreaks(
  sorted: number[],
  classes: number,
): number[] {
  const min = sorted[0];
  const max = sorted[sorted.length - 1];
  const step = (max - min) / classes;
  const breaks: number[] = [];
  for (let i = 1; i < classes; i++) {
    breaks.push(min + step * i);
  }
  return stabilizeBreaks(sorted, breaks, classes);
}

function dedupe(arr: number[]): number[] {
  const out: number[] = [];
  for (const v of arr) {
    if (out.length === 0 || v > out[out.length - 1]) out.push(v);
  }
  return out;
}

function stabilizeBreaks(
  sorted: number[],
  breaks: number[],
  classes: number,
): number[] {
  const target = Math.max(0, classes - 1);
  const deduped = dedupe(breaks);
  if (deduped.length === target) return deduped;
  const unique = dedupe(sorted);
  if (unique.length <= 1) return [];
  const effectiveClasses = Math.min(classes, unique.length);
  const fallback: number[] = [];
  for (let i = 1; i < effectiveClasses; i++) {
    const pos = (i / effectiveClasses) * (unique.length - 1);
    const lo = Math.floor(pos);
    const hi = Math.min(lo + 1, unique.length - 1);
    fallback.push((unique[lo] + unique[hi]) / 2);
  }
  return dedupe(fallback);
}

export interface VisualVariableDraft {
  enabled: boolean;
  field: string;
  method: ClassificationMethod;
  classes: number;
  min: number;
  max: number;
}

export interface SortVariableDraft {
  enabled: boolean;
  field: string;
  order: "ascending" | "descending";
}

export function draftFromVariable(
  variable: NumericVisualVariable | undefined,
  fallbackField: string,
  fallbackRange: [number, number],
): VisualVariableDraft {
  return {
    enabled: Boolean(variable?.field),
    field: variable?.field || fallbackField,
    method: variable?.method || "quantile",
    classes: variable?.classes || variable?.values?.length || 5,
    min: variable?.range?.[0] ?? variable?.values?.[0] ?? fallbackRange[0],
    max:
      variable?.range?.[1] ??
      variable?.values?.[variable.values.length - 1] ??
      fallbackRange[1],
  };
}

export function variableFromDraft(
  draft: VisualVariableDraft,
): NumericVisualVariable | undefined {
  if (!draft.enabled || !draft.field) return undefined;
  return {
    field: draft.field,
    method: draft.method,
    classes: draft.classes,
    range: [draft.min, draft.max],
  };
}

export function sortDraftFromVariable(
  variable: SortVisualVariable | undefined,
  fallbackField: string,
): SortVariableDraft {
  return {
    enabled: Boolean(variable?.field),
    field: variable?.field || fallbackField,
    order: variable?.order || "descending",
  };
}

export function sortVariableFromDraft(
  draft: SortVariableDraft,
): SortVisualVariable | undefined {
  if (!draft.enabled || !draft.field) return undefined;
  return {
    field: draft.field,
    order: draft.order,
  };
}
