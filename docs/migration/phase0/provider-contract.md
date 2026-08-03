# Phase 0 Provider 迁移契约

## 决策结果

Settings 当前公开 24 个 Provider。Phase 0 的决策是：**24 个全部保留并迁移，0 个静默废弃**。这不表示它们已经由 Java 实现，而是规定 Phase 5 必须满足其对应门禁；若未来决定废弃，必须先完成公告、配置迁移和 UI 移除。

| 支持层级 | Provider | Java 验证要求 |
|---|---|---|
| Tier 1 合同门禁 | OpenAI、Anthropic、DeepSeek、Ollama | 每次合并运行离线协议 fixture；发布前运行真实端点认证测试。 |
| Tier 2 认证 | MiniMax、Mistral、Google、xAI、OpenRouter、Cohere、智谱、腾讯混元、通义千问、豆包、Kimi、阶跃星辰、零一万物、SiliconFlow、小米 MiMo、Perplexity、NVIDIA NIM | 共用协议测试通过后，按 Provider 验证 streaming、tool call、usage、错误和取消。 |
| 兼容性专项 | Azure OpenAI、百度千帆、Hugging Face | 在 Java 适配前先确认 URL、鉴权、deployment/model 语义及工具流兼容性；不得仅凭“OpenAI compatible”判定完成。 |

协议分组为 22 个 OpenAI-compatible 和 2 个 Anthropic-compatible（Anthropic、MiniMax）。完整 URL、默认模型、风险、层级和决策位于 `test/phase0/providers/provider-migration.json`。

## 离线协议 fixture

- `openai-compatible.fixture.json`：冻结 chat completion 请求、SSE 文本/tool-call 增量、usage、429 和断流/取消语义。
- `anthropic-compatible.fixture.json`：冻结 Messages 请求、content block 增量、tool use、usage、overloaded error 和取消语义。
- fixture 使用 `${API_KEY}`，不会包含真实凭据，也不会调用公网。

## Java 完成门禁

Provider 只有在以下项目全部通过后才能标记完成：请求投影、流式文本、流式 Tool 参数拼接、usage/token、错误映射、超时、主动取消、断流、代理/base URL 和敏感信息脱敏。Settings 中仍可见但没有认证记录的 Provider 会阻塞 Java-only 发布。

Phase 0 只冻结现状与迁移责任；真实供应商可用性会变化，因此真实端点认证应在发布流水线的受控凭据环境中维护，不放入确定性 CI。
