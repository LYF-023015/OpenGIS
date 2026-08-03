# Phase 5 Provider 迁移矩阵

| Provider | 协议 | Java adapter | 默认地址策略 | 离线门禁 |
|---|---|---|---|---|
| OpenAI | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 1 SSE |
| Anthropic | Anthropic | `AnthropicMessagesClient` | `/v1/messages` | Tier 1 SSE |
| DeepSeek | OpenAI | `OpenAiCompatibleClient` | `/chat/completions` | Tier 1 schema |
| MiniMax | Anthropic | `AnthropicMessagesClient` | `/v1/messages` | Tier 2 schema |
| Mistral | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Google Gemini | OpenAI | `OpenAiCompatibleClient` | OpenAI compatibility endpoint | Tier 2 schema |
| xAI Grok | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Ollama | OpenAI | `OpenAiCompatibleClient` | loopback `/v1/chat/completions` | Tier 1 SSE |
| OpenRouter | OpenAI | `OpenAiCompatibleClient` | `/api/v1/chat/completions` | Tier 2 schema |
| Cohere | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Azure OpenAI | OpenAI | `OpenAiCompatibleClient` | 完整 deployment URL + query | Azure header/query test |
| Baidu Wenxin | OpenAI-compatible 配置 | `OpenAiCompatibleClient` | 用户可覆盖完整兼容 URL | compatibility spike |
| Zhipu GLM | OpenAI | `OpenAiCompatibleClient` | `/api/paas/v4/chat/completions` | Tier 2 schema |
| Tencent Hunyuan | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Alibaba Qwen | OpenAI | `OpenAiCompatibleClient` | compatible-mode `/chat/completions` | Tier 2 schema |
| ByteDance Doubao | OpenAI | `OpenAiCompatibleClient` | `/api/v3/chat/completions` | Tier 2 schema |
| Kimi (Moonshot) | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| StepFun | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| 01.AI Yi | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| SiliconFlow | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Xiaomi MiMo | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Perplexity | OpenAI | `OpenAiCompatibleClient` | `/chat/completions` | Tier 2 schema |
| NVIDIA NIM | OpenAI | `OpenAiCompatibleClient` | `/v1/chat/completions` | Tier 2 schema |
| Hugging Face | OpenAI-compatible 配置 | `OpenAiCompatibleClient` | 用户可覆盖完整兼容 URL | compatibility spike |

“离线门禁通过”表示 Java 请求/响应结构已由本地协议服务器验证，不表示第三方当前模型名、配额、地区和账号权限已被无密钥 CI 证明。外部认证结果应记录日期、endpoint、模型和脱敏错误，不能把真实密钥写入仓库。
