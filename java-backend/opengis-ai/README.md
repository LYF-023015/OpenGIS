# opengis-ai

## 职责

保存 Provider-neutral LLM 模型、`LlmClient` 端口、流式增量、token/usage 和具体 Provider adapter。

## 依赖规则

- 允许依赖：`opengis-common`、`opengis-framework`。
- 禁止依赖：Agent 步骤策略、Tool 执行、Session 存储和 UI。
- 入口标记：`org.opengis.ai.AiModule`。
- 推荐包：`model`、`port`、`provider`、`stream`、`token`。

## 测试

```powershell
./mvnw.cmd -pl opengis-ai -am test
```
