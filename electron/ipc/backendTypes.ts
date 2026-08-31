/** 文件职责：共享基础能力：定义领域数据结构与协议。 */
import type { BrowserWindow } from "electron";

export type BackendRuntime = "java" | "python";
export type BackendState = "stopped" | "starting" | "ready" | "error";

export interface BackendStatus {
  status: BackendState;
  runtime: BackendRuntime;
  port?: number;
  error?: string;
  executablePath?: string;
  serverPath?: string;
  wsToken?: string;
  diagnostics?: string[];
}

export interface BackendProcessManager {
  setMainWindow(window: BrowserWindow | null): void;
  getStatus(): BackendStatus;
  getPort(): number | null;
  getWsToken(): string | null;
  start(): Promise<void>;
  stop(): Promise<void>;
  restart(): Promise<void>;
}
