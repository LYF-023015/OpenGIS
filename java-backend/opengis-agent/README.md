# opengis-agent

## 职责

Agent 模块拥有 Agent 运行编排、生命周期策略与持久化模型。生产执行路径基于 Spring AI 2.0：

```text
AgentApplicationService
  -> SessionCoordinator / Workspace lease
  -> SpringAiAgentRunner
     -> ChatClient
     -> ToolCallingAdvisor       # LLM -> Tool -> LLM 递归循环
     -> RunLifecycleAdvisor      # 回合限制、事件、用量、上下文
     -> OpenGisToolCallingManager
        -> ToolRuntime           # Schema、权限、审批、超时、Artifact
  -> RunArchive / AgentNotificationBridge
```

Spring AI 负责模型抽象、Provider 适配和工具循环；OpenGIS 继续负责 GIS 业务边界。工具不能直接由 Spring AI callback 执行，必须经过 `OpenGisToolCallingManager -> ToolRuntime`，否则会绕过 workspace 权限、用户审批和审计事件。

当前核心组件：

- `RunArchive` 与 `RunIndex`；
- `SessionStore`（同文件内包含 sessions 和 inbox）；
- `AgentProfileStore`（默认 Profile + workspace override）；
- `PermissionRuleStore`；
- `ArtifactStore`、`ContextStore`、`ConversationTitleStore`；
- `ScriptArchive`；
- `SpringAiAgentRunner`、`RunLifecycleAdvisor`、`OpenGisToolCallingManager`。

`RunArchive` 使用 append-only JSONL 结构。关闭 run 时，如果最后一个 ToolCall 或 MessagePart 仍为 pending/running/streaming，会追加一个终态事件，不覆盖历史事件。

旧 `LoopKernel`/`TurnRunner` 与手写 Provider SSE 客户端已由 Spring AI 的 `ChatClient`、`ToolCallingAdvisor` 和模型实现替代并移除。

依赖方向：`agent → knowledge/tool/ai/platform/framework/common`。网络 Controller 不进入本模块。
