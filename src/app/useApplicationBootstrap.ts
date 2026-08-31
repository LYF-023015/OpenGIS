/** 文件职责：前端应用装配：实现该文件名所对应的单一职责。 */
import { useEffect } from "react";
import { backendClient } from "@/shared/backend/backendClient";
import { useAssetStore } from "@/plugins/workspace/assets/model/assetStore";
import { useSettingsStore } from "@/plugins/system/settings/model/settingsStore";
import { builtinRendererPlugins } from "./plugins/builtin";
import { RendererPluginRuntime } from "./plugins/runtime";
import {
  UI_CONTRIBUTIONS_SERVICE,
  uiContributions,
} from "./plugins/uiContributions";

/** Installs renderer plugins and bridges Electron project selection into application state. */
function useRendererPlugins(setWorkspacePath: (path: string) => void) {
  useEffect(() => {
    const plugins = new RendererPluginRuntime(
      builtinRendererPlugins,
      new Map([[UI_CONTRIBUTIONS_SERVICE, uiContributions]]),
    ).start();
    const unsubscribe = window.electronAPI?.onProjectSelected?.(
      (project: { path?: string }) => {
        if (project.path) setWorkspacePath(project.path);
      },
    );

    try {
      window.electronAPI?.signalRendererReady?.();
    } catch {
      // Browser-only development has no Electron lifecycle.
    }

    return () => {
      plugins.dispose();
      unsubscribe?.();
    };
  }, [setWorkspacePath]);
}

/** Keeps the renderer JSON-RPC socket aligned with the Java sidecar lifecycle. */
function useBackendConnection() {
  useEffect(() => {
    const api = window.electronAPI;
    if (!api) return;

    let cancelled = false;
    let token: string | null = null;
    let currentPort: number | null = null;
    let unsubscribeStatus: (() => void) | undefined;
    let unsubscribeToken: (() => void) | undefined;

    const fetchToken = async () => {
      try {
        token = await api.getBackendWsToken();
      } catch (error) {
        console.warn("[App] Failed to fetch WebSocket token:", error);
      }
    };
    const connect = (port?: number | null) => {
      if (cancelled || !port) return;
      currentPort = port;
      backendClient.disconnect();
      backendClient.connect(port, token ?? undefined);
    };

    const initialize = async () => {
      await fetchToken();
      const status = await api.getBackendStatus();
      if (status?.status === "ready") {
        if (!token) await fetchToken();
        connect(status.port);
      }

      unsubscribeStatus = api.onBackendStatusChanged((next) => {
        if (next.status === "ready") {
          currentPort = next.port ?? null;
          if (token) connect(next.port);
          else fetchToken().then(() => connect(currentPort));
        } else if (next.status === "stopped" || next.status === "error") {
          backendClient.disconnect();
        }
      });
      unsubscribeToken = api.onBackendWsToken?.((nextToken: string) => {
        token = nextToken;
        connect(currentPort);
      });
    };

    initialize().catch((error) =>
      console.warn("[App] Backend initialization failed:", error),
    );
    return () => {
      cancelled = true;
      unsubscribeStatus?.();
      unsubscribeToken?.();
      backendClient.disconnect();
    };
  }, []);
}

function useTheme(theme: "dark" | "light" | "system") {
  useEffect(() => {
    const root = document.documentElement;
    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const dark = theme === "system" ? mediaQuery.matches : theme === "dark";
      root.classList.toggle("light", !dark);
      window.electronAPI?.setTitleBarTheme?.(dark);
    };
    apply();
    if (theme !== "system") return;
    mediaQuery.addEventListener("change", apply);
    return () => mediaQuery.removeEventListener("change", apply);
  }, [theme]);
}

/** Runs global lifecycle effects while leaving App as a pure composition root. */
export function useApplicationBootstrap() {
  const loadSettings = useSettingsStore((state) => state.loadFromElectron);
  const theme = useSettingsStore((state) => state.appearance.theme);
  const debugMode = useSettingsStore((state) => state.agent.debugMode);
  const workspacePath = useAssetStore((state) => state.workspacePath);
  const setWorkspacePath = useAssetStore((state) => state.setWorkspacePath);

  useRendererPlugins(setWorkspacePath);
  useBackendConnection();
  useTheme(theme);

  useEffect(() => {
    loadSettings();
  }, [loadSettings]);

  useEffect(() => {
    backendClient
      .send("rpc.debug.set_log_level", { level: debugMode ? "DEBUG" : "INFO" })
      .catch(() => {});
  }, [debugMode]);

  useEffect(() => {
    if (!workspacePath) return;
    backendClient
      .send("rpc.workspace.install_templates", {
        workspace_path: workspacePath,
      })
      .catch(() => {});
  }, [workspacePath]);
}
