/** 文件职责：前端应用装配：验证对应功能的行为与边界。 */
import { describe, expect, it } from "vitest";
import { RendererPluginRuntime } from "./runtime";
import type { RendererPlugin } from "./runtime";

const plugin = (
  id: string,
  requires: string[],
  events: string[],
): RendererPlugin => ({
  descriptor: { id, version: "1.0.0", requires },
  activate: () => {
    events.push(`activate:${id}`);
    return () => events.push(`dispose:${id}`);
  },
});

describe("RendererPluginRuntime", () => {
  it("activates dependencies first and disposes in reverse order", () => {
    const events: string[] = [];
    const runtime = new RendererPluginRuntime([
      plugin("map", ["rpc"], events),
      plugin("rpc", [], events),
    ]).start();
    expect(runtime.mountedPluginIds()).toEqual(["rpc", "map"]);
    runtime.dispose();
    runtime.dispose();
    expect(events).toEqual([
      "activate:rpc",
      "activate:map",
      "dispose:map",
      "dispose:rpc",
    ]);
  });

  it("includes transitive dependencies selected by a profile", () => {
    const events: string[] = [];
    const runtime = new RendererPluginRuntime(
      [
        plugin("unused", [], events),
        plugin("map", ["rpc"], events),
        plugin("rpc", [], events),
      ],
      new Map(),
      { id: "desktop", enabledPlugins: ["map"] },
    ).start();
    expect(runtime.mountedPluginIds()).toEqual(["rpc", "map"]);
  });

  it("rejects missing dependencies and cycles", () => {
    expect(() =>
      new RendererPluginRuntime([plugin("map", ["missing"], [])]).start(),
    ).toThrow("Unknown renderer plugin: missing");
    expect(() =>
      new RendererPluginRuntime([
        plugin("first", ["second"], []),
        plugin("second", ["first"], []),
      ]).start(),
    ).toThrow("first -> second -> first");
  });
});
