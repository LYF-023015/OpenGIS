/** 文件职责：ai 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.assistant.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Phase 0 provider ledger expressed as executable Java configuration. */
public final class ProviderCatalog {
  private static final List<ProviderPreset> PRESETS =
      List.of(
          preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o", "tier-1"),
          anthropic(
              "anthropic",
              "Anthropic",
              "https://api.anthropic.com",
              "claude-sonnet-4-20250514",
              "tier-1"),
          preset("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", "tier-1"),
          anthropic(
              "minimax", "MiniMax", "https://api.minimaxi.com/anthropic", "MiniMax-M2.7", "tier-2"),
          preset(
              "mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-large-latest", "tier-2"),
          preset(
              "google",
              "Google Gemini",
              "https://generativelanguage.googleapis.com/v1beta/openai/",
              "gemini-2.0-flash",
              "tier-2"),
          preset("xai", "xAI Grok", "https://api.x.ai/v1", "grok-3", "tier-2"),
          preset("ollama", "Ollama", "http://localhost:11434/v1", "llama3", "tier-1"),
          preset(
              "openrouter",
              "OpenRouter",
              "https://openrouter.ai/api/v1",
              "meta-llama/llama-3-70b-instruct",
              "tier-2"),
          preset("cohere", "Cohere", "https://api.cohere.ai/v1", "command-r-plus", "tier-2"),
          preset("azure", "Azure OpenAI", "", "gpt-4o", "compatibility-spike"),
          preset(
              "baidu",
              "Baidu Wenxin",
              "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop",
              "ernie-4.0",
              "compatibility-spike"),
          preset("zhipu", "Zhipu GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4", "tier-2"),
          preset(
              "hunyuan",
              "Tencent Hunyuan",
              "https://api.hunyuan.cloud.tencent.com/v1",
              "hunyuan-pro",
              "tier-2"),
          preset(
              "qwen",
              "Alibaba Qwen",
              "https://dashscope.aliyuncs.com/compatible-mode/v1",
              "qwen-max",
              "tier-2"),
          preset(
              "doubao",
              "ByteDance Doubao",
              "https://ark.cn-beijing.volces.com/api/v3",
              "doubao-pro-256k",
              "tier-2"),
          preset(
              "kimi",
              "Kimi (Moonshot)",
              "https://api.moonshot.cn/v1",
              "moonshot-v1-128k",
              "tier-2"),
          preset("stepfun", "StepFun", "https://api.stepfun.com/v1", "step-2-16k", "tier-2"),
          preset("yi", "01.AI Yi", "https://api.lingyiwanwu.com/v1", "yi-large", "tier-2"),
          preset(
              "siliconflow",
              "SiliconFlow",
              "https://api.siliconflow.cn/v1",
              "Qwen/Qwen2.5-72B-Instruct",
              "tier-2"),
          preset(
              "xiaomimimo", "Xiaomi MiMo", "https://api.xiaomimimo.com/v1", "MiMo-7B-RL", "tier-2"),
          preset(
              "perplexity",
              "Perplexity",
              "https://api.perplexity.ai",
              "llama-3.1-sonar-huge-128k-online",
              "tier-2"),
          preset(
              "nvidia",
              "NVIDIA NIM",
              "https://integrate.api.nvidia.com/v1",
              "meta/llama-3.1-405b-instruct",
              "tier-2"),
          preset(
              "huggingface",
              "Hugging Face",
              "https://api-inference.huggingface.co/models",
              "meta-llama/Llama-3.1-70B-Instruct",
              "compatibility-spike"));

  private static final Map<String, ProviderPreset> BY_ID = index();

  private ProviderCatalog() {}

  public static List<ProviderPreset> presets() {
    return PRESETS;
  }

  public static Optional<ProviderPreset> find(String id) {
    return Optional.ofNullable(BY_ID.get(id));
  }

  private static ProviderPreset preset(
      String id, String label, String url, String model, String tier) {
    return new ProviderPreset(id, label, ProviderProtocol.OPENAI, url, model, tier);
  }

  private static ProviderPreset anthropic(
      String id, String label, String url, String model, String tier) {
    return new ProviderPreset(id, label, ProviderProtocol.ANTHROPIC, url, model, tier);
  }

  private static Map<String, ProviderPreset> index() {
    Map<String, ProviderPreset> index = new LinkedHashMap<>();
    PRESETS.forEach(preset -> index.put(preset.id(), preset));
    return Map.copyOf(index);
  }
}
