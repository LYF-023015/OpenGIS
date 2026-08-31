/** 文件职责：pivot 前端功能：实现该文件名所对应的单一职责。 */
import { backendClient } from "@/shared/backend/backendClient";
import type {
  PivotAgentResult,
  PivotData,
  PivotDistributionBucket,
  PivotFieldDistribution,
  PivotFieldStat,
} from "../model/types";

const MAX_ANALYSIS_ROWS = 2000;
const MAX_ANALYSIS_COLUMNS = 48;
const MAX_BUCKETS = 12;

export interface PivotAgentLog {
  id: number;
  stream: "info" | "stdout" | "stderr" | "error";
  text: string;
  ts: number;
}

interface RunPivotAgentOptions {
  onLog?: (log: Omit<PivotAgentLog, "id">) => void;
}

interface JavaPivotReply {
  stats?: PivotFieldStat[];
  distributions?: PivotFieldDistribution[];
  summary?: string;
  duration_ms?: number;
}

/** Local, deterministic fallback used only when the Java sidecar is unavailable. */
export function computePivotAnalysis(data: PivotData): PivotAgentResult {
  if (data.raster) {
    const stats: PivotFieldStat[] = data.raster.rows.map((row) => ({
      field: String(row.band ?? row.Band ?? "Raster"),
      type: "number",
      count: Number(row.valid_pixels ?? 0) || 0,
      nullCount: Number(row.nodata_pixels ?? 0) || 0,
      uniqueCount: 0,
      min: numericValue(row.min),
      max: numericValue(row.max),
      mean: numericValue(row.mean),
    }));
    return {
      stats,
      distributions: [],
      summary: `Raster sample contains ${stats.length} band(s). Review min/max, mean and NoData counts.`,
      engine: "typescript",
    };
  }

  const table = data.table;
  if (!table)
    return {
      stats: [],
      distributions: [],
      summary: "No tabular sample is available.",
      engine: "typescript",
    };
  const rows = table.rows.slice(0, MAX_ANALYSIS_ROWS);
  const columns = table.columns.slice(0, MAX_ANALYSIS_COLUMNS);
  const stats = columns.map((field) => computeFieldStat(field, rows));
  const distributions = stats
    .filter((stat) => stat.count > 0)
    .slice(0, 16)
    .map((stat) => computeDistribution(stat.field, stat.type, rows));
  const numeric = stats.filter((stat) => stat.type === "number").length;
  const categorical = stats.filter(
    (stat) => stat.type === "string" || stat.type === "boolean",
  ).length;
  return {
    stats,
    distributions,
    summary: `Analyzed ${rows.length} record(s) and ${stats.length} field(s): ${numeric} numeric and ${categorical} categorical/text.`,
    engine: "typescript",
  };
}

/** Sends data, never executable source, to the structured Java Pivot RPC. */
export async function runPivotAgent(
  data: PivotData,
  workspacePath?: string | null,
  options: RunPivotAgentOptions = {},
): Promise<PivotAgentResult> {
  const emit = (text: string, stream: PivotAgentLog["stream"] = "info") =>
    options.onLog?.({ stream, text, ts: Date.now() });
  const fallback = computePivotAnalysis(data);
  if (!backendClient.isConnected) {
    emit(
      "Java backend is not connected; using deterministic renderer statistics.\n",
      "error",
    );
    return fallback;
  }

  const payload = {
    workspace_path: workspacePath ?? undefined,
    kind: data.dataKind,
    title: data.title,
    columns: data.table?.columns.slice(0, MAX_ANALYSIS_COLUMNS) ?? [],
    rows: data.table?.rows.slice(0, MAX_ANALYSIS_ROWS) ?? [],
    total_rows: data.table?.totalRows ?? data.table?.rows.length ?? 0,
    sampled: data.table?.sampled ?? false,
    raster_rows: data.raster?.rows ?? [],
  };
  emit(
    `Sending structured Pivot request to Java (${payload.rows.length} rows).\n`,
  );
  try {
    const reply = await backendClient.send<JavaPivotReply>(
      "rpc.analysis.pivot",
      payload,
      45_000,
    );
    if (
      !Array.isArray(reply.stats) ||
      !Array.isArray(reply.distributions) ||
      typeof reply.summary !== "string"
    ) {
      throw new Error("Java Pivot RPC returned an invalid contract");
    }
    emit(`Java Pivot completed: ${reply.stats.length} fields.\n`);
    return {
      stats: reply.stats,
      distributions: reply.distributions,
      summary: reply.summary,
      durationMs: reply.duration_ms ?? null,
      engine: "java",
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emit(`${message}\n`, "error");
    return {
      ...fallback,
      summary: `${fallback.summary} Java analysis failed: ${message}`,
    };
  }
}

function computeFieldStat(
  field: string,
  rows: Record<string, unknown>[],
): PivotFieldStat {
  const values = rows.map((row) => row[field]);
  const nonNull = values.filter((value) => !isNullValue(value));
  const numeric = nonNull
    .map(toNumber)
    .filter((value): value is number => value !== null);
  const unique = new Set(nonNull.map(String));
  const numericLikely =
    nonNull.length > 0 &&
    numeric.length >= Math.max(3, Math.floor(nonNull.length * 0.7));
  if (numericLikely) {
    return {
      field,
      type: "number",
      count: nonNull.length,
      nullCount: values.length - nonNull.length,
      uniqueCount: unique.size,
      min: Math.min(...numeric),
      max: Math.max(...numeric),
      mean: numeric.reduce((sum, value) => sum + value, 0) / numeric.length,
    };
  }
  const booleanLikely =
    nonNull.length > 0 &&
    nonNull.every(
      (value) =>
        typeof value === "boolean" ||
        ["true", "false"].includes(String(value).toLowerCase()),
    );
  const strings = nonNull.map(String);
  return {
    field,
    type: booleanLikely ? "boolean" : "string",
    count: nonNull.length,
    nullCount: values.length - nonNull.length,
    uniqueCount: unique.size,
    min: strings.length
      ? strings.reduce((a, b) => (a.localeCompare(b) <= 0 ? a : b))
      : undefined,
    max: strings.length
      ? strings.reduce((a, b) => (a.localeCompare(b) >= 0 ? a : b))
      : undefined,
  };
}

function computeDistribution(
  field: string,
  type: PivotFieldStat["type"],
  rows: Record<string, unknown>[],
): PivotFieldDistribution {
  const values = rows
    .map((row) => row[field])
    .filter((value) => !isNullValue(value));
  if (!values.length) return { field, type, buckets: [] };
  if (type === "number") {
    const numbers = values
      .map(toNumber)
      .filter((value): value is number => value !== null);
    if (!numbers.length) return { field, type, buckets: [] };
    const min = Math.min(...numbers),
      max = Math.max(...numbers);
    if (min === max)
      return {
        field,
        type,
        buckets: [
          { label: formatValue(min), count: numbers.length, probability: 1 },
        ],
      };
    const size = Math.min(
      MAX_BUCKETS,
      Math.max(4, Math.round(Math.sqrt(numbers.length))),
    );
    const counts = Array.from({ length: size }, () => 0);
    numbers.forEach(
      (value) =>
        counts[
          Math.min(size - 1, Math.floor(((value - min) / (max - min)) * size))
        ]++,
    );
    return {
      field,
      type,
      buckets: counts.map((count, index) => ({
        label: `${formatValue(min + ((max - min) * index) / size)}-${formatValue(min + ((max - min) * (index + 1)) / size)}`,
        count,
        probability: count / numbers.length,
      })),
    };
  }
  const counts = new Map<string, number>();
  values.forEach((value) =>
    counts.set(String(value), (counts.get(String(value)) ?? 0) + 1),
  );
  const buckets: PivotDistributionBucket[] = [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, MAX_BUCKETS)
    .map(([label, count]) => ({
      label,
      count,
      probability: count / values.length,
    }));
  return { field, type, buckets };
}

function isNullValue(value: unknown): boolean {
  return (
    value === null ||
    value === undefined ||
    value === "" ||
    (typeof value === "number" && Number.isNaN(value))
  );
}

function toNumber(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (
    typeof value === "boolean" ||
    value === null ||
    value === undefined ||
    String(value).trim() === ""
  )
    return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function numericValue(value: unknown): number | undefined {
  return toNumber(value) ?? undefined;
}

function formatValue(value: number): string {
  if (Math.abs(value) >= 1000 || Math.abs(value) < 0.01)
    return value.toExponential(2);
  return Number(value.toFixed(3)).toString();
}
