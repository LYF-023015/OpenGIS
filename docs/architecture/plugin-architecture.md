# OpenGIS 插件架构

OpenGIS 保留 Electron + React 与 Java 21 + Spring Boot。插件化只负责能力装配，不替换应用框架，也不把每个类都变成插件。

```text
React Renderer
  └─ RendererPluginRuntime
      ├─ assistant   ├─ workspace   ├─ gis
      ├─ automation  └─ system
      └─ SidebarContribution → MainLayout

Spring Boot Server
  └─ core.PluginRuntime
      ├─ core-tools      ├─ memory-tools
      ├─ gis-tools       └─ execution-tools
      └─ ToolRegistry → ToolRuntime
```

前端五个插件入口位于 `src/plugins/*/*Plugin.tsx`。它们声明稳定 ID、依赖和 UI 贡献；React 组件、Zustand Store 与 MapLibre 实现继续留在插件内部。后端插件协议位于 `org.opengis.core.plugin`，跨领域 Tool 适配器位于 `org.opengis.tool.plugins`。

## 后端依赖方向

```text
server → assistant / automation / gis / tool → core
tool.plugins → assistant / automation / gis / tool / core
```

- `core`：协议、并发、持久化、安全和插件运行时，不依赖业务域。
- `assistant`：Agent、模型 Provider、上下文与 Memory。
- `automation`：Workflow、Worker 和隔离 Java Code Runtime。
- `gis`：空间数据、矢量、栅格、CRS 和 GIS Adapter。
- `tool`：工具协议、权限、注册、执行和跨领域插件适配器。
- `server`：唯一 Spring Boot 组合根、RPC 和传输层。

这些边界由 `ModuleDependencyTest`（ArchUnit）、插件生命周期测试和 Maven 全量测试约束。物理构建只保留 `server` 与 `script-sdk` 两个 Maven 模块。

## 插件契约

前后端运行时都要求稳定 ID、显式依赖、确定性启动、失败回滚和逆序卸载。当前插件随应用静态编译，不支持任意 JAR、远程 JavaScript、运行时下载或热更新。

插件适合独立能力和跨领域适配器；实体、值对象、算法内部类、Repository、Store 和普通 React 子组件仍是领域实现。源码拆分同样遵守“独立变化才独立成文件”，避免纯转发文件和十几行样板对象。

## 新增插件

1. 选择稳定且唯一的小写 ID。
2. 声明显式依赖，不依赖偶然启动顺序。
3. 只从 Context 获取最小宿主服务。
4. 注册、订阅和副作用必须返回可关闭句柄。
5. 覆盖安装顺序、卸载顺序、回滚和功能契约测试。
6. 算法继续留在所属领域，只有装配入口和跨领域适配器进入插件层。
