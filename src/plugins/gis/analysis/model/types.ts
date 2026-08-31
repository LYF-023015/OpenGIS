/** 文件职责：pivot 前端功能：实现该文件名所对应的单一职责。 */
import { create } from "zustand";
import type { MapLayerDefinition } from "@/shared/geo";
import type { FileNode } from "@/plugins/workspace/assets/model/assetStore";

export type PivotTarget =
  | { kind: "layer"; layerId: string; name: string }
  | {
      kind: "file";
      path: string;
      name: string;
      extension: string;
      size: number;
    };

export interface PivotTable {
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows: number | null;
  sampled: boolean;
  sourceLabel: string;
}

export interface PivotRasterStats {
  rows: Array<Record<string, unknown>>;
  meta: Array<{ label: string; value: string }>;
}

export interface PivotData {
  target: PivotTarget;
  dataKind: "table" | "vector" | "raster";
  title: string;
  table?: PivotTable;
  raster?: PivotRasterStats;
  layer?: MapLayerDefinition;
  warning?: string;
}

export interface PivotFieldStat {
  field: string;
  type: "number" | "string" | "boolean" | "date" | "unknown";
  count: number;
  nullCount: number;
  uniqueCount: number;
  min?: number | string;
  max?: number | string;
  mean?: number;
}

export interface PivotDistributionBucket {
  label: string;
  count: number;
  probability: number;
}

export interface PivotFieldDistribution {
  field: string;
  type: PivotFieldStat["type"];
  buckets: PivotDistributionBucket[];
}

export interface PivotAgentResult {
  stats: PivotFieldStat[];
  distributions: PivotFieldDistribution[];
  summary: string;
  durationMs?: number | null;
  engine: "java" | "typescript";
}

export function targetFromLayer(layer: MapLayerDefinition): PivotTarget {
  return { kind: "layer", layerId: layer.id, name: layer.name };
}

export function targetFromFile(node: FileNode): PivotTarget {
  return {
    kind: "file",
    path: node.path,
    name: node.name,
    extension: node.extension.toLowerCase(),
    size: node.size,
  };
}

interface PivotStore {
  isOpen: boolean;
  target: PivotTarget | null;
  mode: "data" | "agent";
  open: (target: PivotTarget) => void;
  close: () => void;
  setMode: (mode: "data" | "agent") => void;
}

export const usePivotStore = create<PivotStore>((set) => ({
  isOpen: false,
  target: null,
  mode: "data",
  open: (target) => set({ isOpen: true, target, mode: "data" }),
  close: () => set({ isOpen: false, target: null, mode: "data" }),
  setMode: (mode) => set({ mode }),
}));
