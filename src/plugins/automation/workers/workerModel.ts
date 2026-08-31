/** 文件职责：集中定义 Worker 面板共享的数据结构、状态判断与格式化函数。 */
export interface WorkerLog {
  ts: number;
  stream: string;
  text: string;
}

export interface WorkerResources {
  available?: boolean;
  cpu_percent?: number | null;
  rss_bytes?: number | null;
  rss_mb?: number | null;
  elapsed?: string | null;
  sampled_at?: number;
  error?: string;
}

export interface ResidentWorker {
  id: string;
  name: string;
  description?: string;
  status: string;
  pid?: number | null;
  folder?: string;
  script_path?: string;
  last_error?: string | null;
  created_at?: number;
  updated_at?: number;
  started_at?: number | null;
  stopped_at?: number | null;
  returncode?: number | null;
  resources?: WorkerResources;
  logs?: WorkerLog[];
  manifest?: {
    schema_version?: number;
    kind?: string;
    entrypoint?: string;
    layers?: Array<Record<string, unknown>>;
  };
  package?: {
    schema_version?: number;
    entrypoint?: string;
    has_readme?: boolean;
    has_config?: boolean;
    src_files?: string[];
  };
}

export interface ResourceSample {
  ts: number;
  cpu: number | null;
  memory: number | null;
}

export const ACTIVE_WORKER_STATUSES = new Set(["starting", "running"]);
export const WORKER_LOG_PREVIEW_LIMIT = 50;
export const WORKER_RESOURCE_HISTORY_LIMIT = 40;

export function statusClass(status: string): string {
  switch (status) {
    case "running":
      return "bg-accent-success animate-pulse";
    case "starting":
      return "bg-accent-warning animate-pulse";
    case "failed":
      return "bg-accent-danger";
    case "paused":
      return "bg-accent-warning";
    default:
      return "bg-text-muted";
  }
}

export function mergeWorkerLogs(
  previous: ResidentWorker[],
  incoming: ResidentWorker[],
): ResidentWorker[] {
  const previousById = new Map(previous.map((worker) => [worker.id, worker]));
  return incoming.map((worker) => {
    const previousWorker = previousById.get(worker.id);
    if (!previousWorker || (worker.logs?.length ?? 0) > 0) return worker;
    return { ...worker, logs: previousWorker.logs ?? [] };
  });
}

export function formatTime(ts: number): string {
  if (!Number.isFinite(ts)) return "--:--:--";
  return new Date(ts * 1000).toLocaleTimeString();
}

export function formatDuration(
  startedAt?: number | null,
  stoppedAt?: number | null,
): string {
  if (!startedAt) return "-";
  const end = stoppedAt || Date.now() / 1000;
  const seconds = Math.max(0, Math.floor(end - startedAt));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

export function fileNameFromPath(filePath: string): string {
  const normalized = filePath.replace(/\\/g, "/");
  return normalized.split("/").pop() || "main.py";
}
