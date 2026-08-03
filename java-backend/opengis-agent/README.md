# opengis-agent

## 职责

Agent 模块拥有 Agent 生命周期和持久化模型。Phase 3 已实现：

- `RunArchive` 与 `RunIndex`；
- `SessionStore`（同文件内包含 sessions 和 inbox）；
- `AgentProfileStore`（5 个 Python 默认 Profile + workspace override）；
- `PermissionRuleStore`；
- `ArtifactStore`、`ContextStore`、`ConversationTitleStore`；
- `ScriptArchive`。

`RunArchive` 保持 Python 的 append-only JSONL 结构。关闭 run 时，如果最后一个 ToolCall 或 MessagePart 仍为 pending/running/streaming，会追加一个终态事件，不覆盖历史事件。

依赖方向：`agent → knowledge/tool/ai/platform/framework/common`。网络 Controller 不进入本模块。
