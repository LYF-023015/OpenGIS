/** 文件职责：前端应用装配：实现该文件名所对应的单一职责。 */
import { backendClient } from "@/shared/backend/backendClient";
import { globalDispatcher } from "@/shared/backend/rpc/dispatcher";
import { globalRegistry } from "@/shared/backend/rpc/registry";
import { registerAllHandlers } from "@/shared/backend/rpc/handlers/register";
import { installExtensions } from "@/plugins/gis/map/extensions";
import { assistantPlugin } from "@/plugins/assistant/AssistantPlugin";
import { automationPlugin } from "@/plugins/automation/AutomationPlugin";
import { gisPlugin } from "@/plugins/gis/GisPlugin";
import { systemPlugin } from "@/plugins/system/SystemPlugin";
import { workspacePlugin } from "@/plugins/workspace/WorkspacePlugin";
import type { RendererPlugin } from "./runtime";

const rpcBridgePlugin: RendererPlugin = {
  descriptor: { id: "rpc-bridge", version: "1.0.0" },
  activate: () => {
    registerAllHandlers(globalRegistry, { override: true });
    backendClient.setDispatcher(globalDispatcher);
    return () => {
      backendClient.setDispatcher(null);
      globalRegistry.clear();
    };
  },
};

const mapExtensionsPlugin: RendererPlugin = {
  descriptor: {
    id: "map-extensions",
    version: "1.0.0",
    requires: ["rpc-bridge"],
  },
  activate: () => installExtensions(),
};

export const builtinRendererPlugins: readonly RendererPlugin[] = [
  rpcBridgePlugin,
  mapExtensionsPlugin,
  assistantPlugin,
  workspacePlugin,
  gisPlugin,
  automationPlugin,
  systemPlugin,
];
