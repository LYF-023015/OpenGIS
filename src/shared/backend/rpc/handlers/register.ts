/** 文件职责：实现 register 的单一职责；调用关系从同级主入口文件进入。 */
/**
 * 一次性把所有 handler 注册到 registry。
 *
 * 使用：
 *   import { registerAllHandlers } from '@/shared/backend/rpc/handlers/register';
 *   registerAllHandlers(globalRegistry);
 */

import type { HandlerRegistry, RpcHandler } from "../registry";
import { agentHandlers } from "./agent";
import { askHandlers } from "./ask";
import { chatHandlers } from "./chat";
import { fsHandlers } from "./fs";
import { layoutHandlers } from "./layout";
import { dynamicHandlers } from "./map/dynamic";
import { exportHandlers } from "./map/export";
import { layerHandlers } from "./map/layers";
import { rasterHandlers } from "./map/raster";
import { styleHandlers } from "./map/style";
import { viewHandlers } from "./map/view";

const mapHandlers: Record<string, RpcHandler> = {
  ...layerHandlers,
  ...rasterHandlers,
  ...styleHandlers,
  ...exportHandlers,
  ...viewHandlers,
  ...dynamicHandlers,
};

export const ALL_HANDLER_GROUPS = {
  map: mapHandlers,
  chat: chatHandlers,
  ask: askHandlers,
  fs: fsHandlers,
  layout: layoutHandlers,
  agent: agentHandlers,
};

export function registerAllHandlers(
  registry: HandlerRegistry,
  options: { override?: boolean } = {},
): string[] {
  const registered: string[] = [];
  for (const group of Object.values(ALL_HANDLER_GROUPS)) {
    for (const [method, handler] of Object.entries(group)) {
      if (options.override) {
        registry.override(method, handler);
      } else {
        registry.register(method, handler);
      }
      registered.push(method);
    }
  }
  return registered.sort();
}

/** 列出当前注册表应覆盖的全部 method 名（便于测试断言）。 */
export function listAllMethods(): string[] {
  const methods: string[] = [];
  for (const group of Object.values(ALL_HANDLER_GROUPS)) {
    methods.push(...Object.keys(group));
  }
  return methods.sort();
}
