# 前端源码怎么读

前端仍是 React + TypeScript。源码只有四个基础入口和五个业务插件域，阅读顺序固定如下：

1. `main.tsx`：挂载 React。
2. `app/App.tsx`：应用装配入口。
3. `app/plugins/builtin.ts`：注册内置插件。
4. `shell/MainLayout.tsx`：工作台外壳，只消费插件贡献，不维护业务清单。
5. `plugins/<领域>/<领域>Plugin.tsx`：一个领域的唯一公开入口。
6. 需要修改细节时，再进入该插件下的功能目录。

```text
src/
├─ app/          # 启动、国际化、插件运行时
├─ shell/        # 工作台布局
├─ plugins/      # assistant / workspace / gis / automation / system
├─ shared/       # 跨插件协议、通信、GIS 基础类型和通用 UI
└─ main.tsx
```

插件入口负责声明 ID、依赖、页面和侧边栏贡献；业务实现仍使用普通 React、Zustand 和 TypeScript。子功能按 `model`、`data`、`editor`、`messages`、`style` 等业务含义组织，不建立统一的 `components` 大仓库。

拆分规则是“能够独立变化、复用或测试，才独立成文件”。一次性小组件、单调用方短函数、纯转发出口和包内小类型并回最近的主文件，避免插件化制造样板代码。
