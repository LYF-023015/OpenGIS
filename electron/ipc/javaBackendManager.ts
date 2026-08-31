/** 文件职责：共享基础能力：承载该领域的核心业务流程。 */
import { app, BrowserWindow } from "electron";
import { ChildProcess, execFile, spawn } from "child_process";
import { createHash } from "crypto";
import { existsSync, readFileSync, WriteStream } from "fs";
import { mkdir, rename, writeFile } from "fs/promises";
import net from "net";
import { dirname, join } from "path";
import { getLogDir, openLogFile } from "../logger";
import type { BackendProcessManager, BackendStatus } from "./backendTypes";

const STARTUP_TIMEOUT_MS = 45_000;
const LAST_GOOD_FILE = "java-backend-last-good.json";

function executable(name: string): string {
  return process.platform === "win32" ? `${name}.exe` : name;
}

function timestamp(): string {
  return new Date().toISOString();
}

/** Owns the bundled Java sidecar process used by the desktop application. */
export class JavaBackendManager implements BackendProcessManager {
  private child: ChildProcess | null = null;
  private mainWindow: BrowserWindow | null = null;
  private logStream: WriteStream | null = null;
  private port = 0;
  private wsToken = "";
  private stopping = false;
  private restartAttempts = 0;
  private status: BackendStatus = { status: "stopped", runtime: "java" };

  setMainWindow(window: BrowserWindow | null): void {
    this.mainWindow = window;
  }

  getStatus(): BackendStatus {
    return {
      ...this.status,
      diagnostics: [...(this.status.diagnostics ?? [])],
    };
  }

  getPort(): number | null {
    return this.status.status === "ready" ? this.port : null;
  }

  getWsToken(): string | null {
    return this.status.status === "ready" ? this.wsToken : null;
  }

  async start(): Promise<void> {
    if (this.status.status === "starting" || this.status.status === "ready")
      return;

    this.stopping = false;
    this.port = await this.findAvailablePort();
    this.wsToken = "";
    const paths = this.resolveRuntimePaths();
    const diagnostics = this.validateRuntime(paths.javaPath, paths.serverPath);
    this.status = {
      status: "starting",
      runtime: "java",
      port: this.port,
      executablePath: paths.javaPath,
      serverPath: paths.serverPath,
      diagnostics,
    };
    this.broadcastStatus();

    if (diagnostics.length > 0) {
      const message = diagnostics.join("\n");
      this.status = { ...this.status, status: "error", error: message };
      this.broadcastStatus();
      throw new Error(message);
    }

    const logDir = getLogDir() ?? join(app.getPath("userData"), "logs");
    this.logStream = openLogFile(
      `java-sidecar-${new Date().toISOString().slice(0, 10)}.log`,
    );
    const args = [
      "-jar",
      paths.serverPath,
      "--host",
      "127.0.0.1",
      "--port",
      String(this.port),
      "--log-dir",
      logDir,
    ];
    this.logStream?.write(
      `${timestamp()} [INFO] starting ${paths.javaPath} ${args.join(" ")}\n`,
    );

    this.child = spawn(paths.javaPath, args, {
      cwd: dirname(paths.serverPath),
      env: { ...process.env, OPENGIS_DESKTOP: "1" },
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });

    this.child.stdout?.on("data", (value: Buffer) =>
      this.handleOutput(value, "OUT"),
    );
    this.child.stderr?.on("data", (value: Buffer) =>
      this.handleOutput(value, "ERR"),
    );
    this.child.once("error", (error) => this.handleProcessError(error));
    this.child.once("exit", (code, signal) => this.handleExit(code, signal));

    try {
      await this.waitForReady(STARTUP_TIMEOUT_MS);
      this.restartAttempts = 0;
      await this.recordLastGood(paths.javaPath, paths.serverPath);
    } catch (error) {
      await this.stop();
      this.status = {
        ...this.status,
        status: "error",
        error: error instanceof Error ? error.message : String(error),
      };
      this.broadcastStatus();
      throw error;
    }
  }

  async stop(): Promise<void> {
    this.stopping = true;
    const child = this.child;
    this.child = null;
    this.status = { status: "stopped", runtime: "java" };
    this.broadcastStatus();
    if (!child || child.exitCode !== null) {
      this.closeLog();
      return;
    }

    await new Promise<void>((resolve) => {
      let finished = false;
      const done = () => {
        if (finished) return;
        finished = true;
        this.closeLog();
        resolve();
      };
      child.once("exit", done);
      if (process.platform === "win32" && child.pid) {
        execFile(
          "taskkill",
          ["/T", "/PID", String(child.pid)],
          { timeout: 5_000 },
          () => undefined,
        );
      } else {
        child.kill("SIGTERM");
      }
      setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
          if (process.platform === "win32" && child.pid) {
            execFile(
              "taskkill",
              ["/F", "/T", "/PID", String(child.pid)],
              { timeout: 5_000 },
              () => done(),
            );
          } else {
            child.kill("SIGKILL");
            done();
          }
        } else {
          done();
        }
      }, 5_000);
    });
  }

  async restart(): Promise<void> {
    await this.stop();
    await this.start();
  }

  private resolveRuntimePaths(): { javaPath: string; serverPath: string } {
    if (app.isPackaged) {
      return {
        javaPath: join(
          process.resourcesPath,
          "java-runtime",
          "bin",
          executable("java"),
        ),
        serverPath: join(
          process.resourcesPath,
          "java-backend",
          "opengis-server.jar",
        ),
      };
    }
    const root = join(__dirname, "../..");
    const bundledJava = join(
      root,
      "java-backend",
      "server",
      "target",
      "runtime",
      "bin",
      executable("java"),
    );
    const serverPath = join(
      root,
      "java-backend",
      "server",
      "target",
      "opengis-server-0.1.0-SNAPSHOT.jar",
    );
    return {
      javaPath: existsSync(bundledJava) ? bundledJava : executable("java"),
      serverPath,
    };
  }

  private validateRuntime(javaPath: string, serverPath: string): string[] {
    const diagnostics: string[] = [];
    if (app.isPackaged && !existsSync(javaPath))
      diagnostics.push(`Bundled Java runtime is missing: ${javaPath}`);
    if (!existsSync(serverPath))
      diagnostics.push(`OpenGIS Java server is missing: ${serverPath}`);
    try {
      if (
        existsSync(serverPath) &&
        readFileSync(serverPath, { encoding: null }).length === 0
      ) {
        diagnostics.push(`OpenGIS Java server is empty: ${serverPath}`);
      }
    } catch (error) {
      diagnostics.push(`Cannot read OpenGIS Java server: ${String(error)}`);
    }
    return diagnostics;
  }

  private handleOutput(value: Buffer, stream: "OUT" | "ERR"): void {
    const output = value.toString();
    this.logStream?.write(
      `${timestamp()} [${stream}] ${output}${output.endsWith("\n") ? "" : "\n"}`,
    );
    const token = output.match(/OPENGIS_WS_TOKEN=(\S+)/)?.[1];
    if (token) {
      this.wsToken = token;
      this.send("backend:ws-token", token);
    }
    if (output.includes("OPENGIS_READY")) {
      this.status = {
        ...this.status,
        status: "ready",
        port: this.port,
        wsToken: this.wsToken || undefined,
      };
      this.broadcastStatus();
    }
    const trimmed = output.trim();
    if (trimmed)
      (stream === "ERR" ? console.error : console.log)(`[Java] ${trimmed}`);
  }

  private handleProcessError(error: Error): void {
    this.status = {
      ...this.status,
      status: "error",
      error: `Failed to start Java backend: ${error.message}`,
    };
    this.broadcastStatus();
  }

  private handleExit(code: number | null, signal: NodeJS.Signals | null): void {
    this.logStream?.write(
      `${timestamp()} [INFO] process exited code=${code} signal=${signal}\n`,
    );
    this.child = null;
    this.closeLog();
    if (this.stopping) return;

    const error = `Java backend exited unexpectedly (code=${code}, signal=${signal ?? "none"})`;
    this.status = { ...this.status, status: "error", error };
    this.broadcastStatus();
    if (this.restartAttempts >= 3) return;
    this.restartAttempts += 1;
    setTimeout(() => {
      if (!this.stopping)
        this.start().catch((restartError) =>
          console.error("[Java] restart failed", restartError),
        );
    }, 2_000 * this.restartAttempts);
  }

  private waitForReady(timeoutMs: number): Promise<void> {
    return new Promise((resolve, reject) => {
      const startedAt = Date.now();
      const check = () => {
        if (this.status.status === "ready") return resolve();
        if (this.status.status === "error")
          return reject(new Error(this.status.error ?? "Java backend failed"));
        if (Date.now() - startedAt >= timeoutMs)
          return reject(
            new Error(`Java backend startup timed out after ${timeoutMs}ms`),
          );
        setTimeout(check, 200);
      };
      check();
    });
  }

  private findAvailablePort(): Promise<number> {
    return new Promise((resolve, reject) => {
      const server = net.createServer();
      server.once("error", reject);
      server.listen(0, "127.0.0.1", () => {
        const address = server.address();
        if (!address || typeof address === "string")
          return reject(new Error("Unable to allocate backend port"));
        server.close(() => resolve(address.port));
      });
    });
  }

  private async recordLastGood(
    javaPath: string,
    serverPath: string,
  ): Promise<void> {
    const target = join(app.getPath("userData"), LAST_GOOD_FILE);
    const temporary = `${target}.tmp`;
    const checksum = createHash("sha256")
      .update(readFileSync(serverPath))
      .digest("hex");
    await mkdir(dirname(target), { recursive: true });
    await writeFile(
      temporary,
      JSON.stringify(
        {
          runtime: "java",
          appVersion: app.getVersion(),
          serverSha256: checksum,
          javaPath,
          serverPath,
          verifiedAt: new Date().toISOString(),
        },
        null,
        2,
      ),
      "utf8",
    );
    await rename(temporary, target);
  }

  private broadcastStatus(): void {
    this.send("backend:status-changed", this.getStatus());
  }

  private send(channel: string, payload: unknown): void {
    if (this.mainWindow && !this.mainWindow.isDestroyed())
      this.mainWindow.webContents.send(channel, payload);
  }

  private closeLog(): void {
    this.logStream?.end();
    this.logStream = null;
  }
}
