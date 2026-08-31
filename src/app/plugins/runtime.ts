/** 文件职责：定义并运行前端插件协议。 */
export interface PluginDescriptor {
  id: string;
  version: string;
  requires?: readonly string[];
}

export type PluginDisposer = () => void;

export interface RendererPluginContext {
  readonly services: ReadonlyMap<string, unknown>;
  require<T>(key: string): T;
}

export interface RendererPlugin {
  readonly descriptor: PluginDescriptor;
  activate(context: RendererPluginContext): void | PluginDisposer;
}

export interface PluginProfile {
  id: string;
  enabledPlugins?: readonly string[];
}

const VALID_ID = /^[a-z0-9][a-z0-9._-]*$/;

export class RendererPluginRuntime {
  private readonly plugins = new Map<string, RendererPlugin>();
  private readonly disposers = new Map<string, PluginDisposer>();
  private started = false;

  constructor(
    plugins: readonly RendererPlugin[],
    services: ReadonlyMap<string, unknown> = new Map(),
    private readonly profile: PluginProfile = { id: "desktop" },
  ) {
    this.context = {
      services,
      require: <T>(key: string): T => {
        if (!services.has(key))
          throw new Error(`Missing renderer host service: ${key}`);
        return services.get(key) as T;
      },
    };
    for (const plugin of plugins) {
      const { id, version } = plugin.descriptor;
      if (!VALID_ID.test(id) || !version.trim())
        throw new Error(`Invalid renderer plugin: ${id}`);
      if (this.plugins.has(id))
        throw new Error(`Duplicate renderer plugin: ${id}`);
      this.plugins.set(id, plugin);
    }
  }

  private readonly context: RendererPluginContext;

  start(): this {
    if (this.started) return this;
    try {
      for (const plugin of this.resolveOrder()) {
        const dispose = plugin.activate(this.context);
        this.disposers.set(plugin.descriptor.id, dispose ?? (() => {}));
      }
      this.started = true;
      return this;
    } catch (error) {
      this.disposeMounted();
      throw error;
    }
  }

  dispose(): void {
    this.disposeMounted();
    this.started = false;
  }

  mountedPluginIds(): string[] {
    return [...this.disposers.keys()];
  }

  private resolveOrder(): RendererPlugin[] {
    const selected = this.selectPlugins();
    const ordered: RendererPlugin[] = [];
    const state = new Map<string, "active" | "done">();
    const path: string[] = [];
    const visit = (id: string): void => {
      if (state.get(id) === "done") return;
      if (state.get(id) === "active") {
        const start = Math.max(0, path.indexOf(id));
        throw new Error(
          `Renderer plugin dependency cycle: ${[...path.slice(start), id].join(" -> ")}`,
        );
      }
      const plugin = this.plugins.get(id);
      if (!plugin) throw new Error(`Unknown renderer plugin: ${id}`);
      state.set(id, "active");
      path.push(id);
      for (const dependency of [...(plugin.descriptor.requires ?? [])].sort()) {
        if (!this.plugins.has(dependency))
          throw new Error(`Unknown renderer plugin: ${dependency}`);
        if (!selected.has(dependency))
          throw new Error(`Disabled renderer plugin dependency: ${dependency}`);
        visit(dependency);
      }
      path.pop();
      state.set(id, "done");
      ordered.push(plugin);
    };
    for (const id of [...selected].sort()) visit(id);
    return ordered;
  }

  private selectPlugins(): Set<string> {
    const roots = this.profile.enabledPlugins;
    if (!roots?.length) return new Set(this.plugins.keys());
    const selected = new Set<string>();
    const pending = [...roots];
    while (pending.length) {
      const id = pending.shift()!;
      const plugin = this.plugins.get(id);
      if (!plugin)
        throw new Error(
          `Unknown renderer plugin in profile ${this.profile.id}: ${id}`,
        );
      if (selected.has(id)) continue;
      selected.add(id);
      pending.push(...(plugin.descriptor.requires ?? []));
    }
    return selected;
  }

  private disposeMounted(): void {
    const entries = [...this.disposers.entries()].reverse();
    this.disposers.clear();
    const failures: unknown[] = [];
    for (const [, dispose] of entries) {
      try {
        dispose();
      } catch (error) {
        failures.push(error);
      }
    }
    if (failures.length) {
      const error = new Error("Renderer plugin disposal failed");
      Object.assign(error, { failures });
      throw error;
    }
  }
}
