# 前端插件入口

每个一级目录是一个业务能力域，并且只有一个 `*Plugin.tsx` 公开入口。`app` 和 `shell` 只能通过这些入口装配业务界面。

| 插件域 | 唯一入口 | 包含的功能 |
| --- | --- | --- |
| `assistant` | `AssistantPlugin.tsx` | Chat、Approval |
| `workspace` | `WorkspacePlugin.tsx` | Assets、文件查看、Java Script Runner |
| `gis` | `GisPlugin.tsx` | Map、Layers、Analysis、Layout、Operations |
| `automation` | `AutomationPlugin.tsx` | Workflows、Workers、Runs |
| `system` | `SystemPlugin.tsx` | Settings、Tool/Skill Catalog |

阅读某项功能时，先从所属插件入口找到它的主 `*View.tsx` 或 `*Panel.tsx`，再进入同级 `model`、`data`、`editor` 等实现目录。插件是装配边界，不要求每个组件、Store 或算法都成为插件。
