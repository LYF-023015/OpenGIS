# opengis-tool

## 职责

`opengis-tool` 是统一工具运行时，不依赖 Spring、Agent Loop 或具体 GIS 引擎。阅读顺序固定为：

1. `api/`：`ToolDefinition`、`ToolCall`、`ToolResult`、`ArtifactRef`、`OpenGisTool`、`UiRpcPort`。
2. `context/`：显式取消、执行身份和生命周期事件。
3. `registry/`：重复安全注册表和 JSON Schema 参数校验。
4. `permission/`：持久化规则、Profile override、风险规则、默认策略。
5. `runtime/`：唯一执行流水线、结果规范化、截断和 Artifact 落盘。
6. `builtin/`：按风险顺序组合的真实工具适配器。

## 依赖规则

- 允许依赖：`opengis-common`、`opengis-framework`、`opengis-platform`。
- 禁止依赖：Agent、Workflow、GIS、Worker、Server。
- Spring Bean 组合和 JSON-RPC 适配位于 `opengis-server`。
- Agent 以后只能调用 `ToolRuntime`，不能绕过权限或直接调用工具实现。

## 安全边界

- 文件路径必须位于当前 workspace，且检查已存在祖先的真实路径以阻止符号链接逃逸。
- 写入/网络/破坏性操作先经过 `PermissionRuntime`；`ask` 通过 `rpc.ui.ask.confirm`。
- Shell 只接收 `argv`，使用 `ProcessBuilder` 直接执行，不解析拼接后的命令字符串。
- 超大输出写入 `.opengis/runs/{run_id}/artifacts/`，主结果仅保留摘要和指针。
- 取消显式传入 Tool、UI 请求、文件扫描和子进程轮询，不使用 `ThreadLocal`。

## 测试

```powershell
./mvnw.cmd -pl opengis-tool -am test
```

每个已激活定义都会参数化执行成功、非法参数、拒绝、取消、异常五类运行时契约；文件、权限、Shell、UI、报告、学术、CSV/GIS 和 Artifact 另有真实集成测试。
