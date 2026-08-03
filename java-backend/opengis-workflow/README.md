# opengis-workflow

Workflow 模块拥有 schema v2、DAG 校验、Queue、节点执行状态与恢复策略。持久化路径为：

```text
.opengis/workflows/<workflow_id>.flow.json
.opengis/workflow_steps/step<index>_<node_id>.md
.opengis/workflow_runs/<run_id>.json
.opengis/sessions.json                 # queue inbox
```

`WorkflowEngine` 只依赖 `WorkflowNodeRunner` 端口，不依赖 Spring 或 Renderer。服务端适配器把 `agent_task` 接到 `AgentApplicationService` child session，把 `tool_call` 接到唯一的 `ToolRuntime`。完整学习说明见 [`../../docs/migration/phase6/README.md`](../../docs/migration/phase6/README.md)。
