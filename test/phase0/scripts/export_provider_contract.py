"""Export public provider migration decisions and provider-neutral wire fixtures."""

from __future__ import annotations

import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / "src" / "features" / "settings" / "providerMap.ts"
OUT = REPO / "test" / "phase0" / "providers"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    pattern = re.compile(
        r"\{\s*id:\s*'([^']+)',\s*label:\s*'([^']+)',\s*protocol:\s*'([^']+)',\s*baseURL:\s*'([^']*)',\s*defaultModel:\s*'([^']+)'\s*\}"
    )
    providers = []
    tier1 = {"openai", "anthropic", "deepseek", "ollama"}
    special = {
        "azure": "requires deployment-specific base URL and API version certification",
        "baidu": "configured URL is not a generic /chat/completions root; requires a dedicated compatibility spike",
        "huggingface": "configured inference endpoint is not guaranteed to expose OpenAI chat/tool streaming",
    }
    for provider_id, label, protocol, base_url, default_model in pattern.findall(SOURCE.read_text(encoding="utf-8")):
        providers.append({
            "id": provider_id,
            "label": label,
            "current_protocol": protocol,
            "base_url": base_url,
            "default_model": default_model,
            "decision": "migrate",
            "java_adapter": f"{protocol}-compatible",
            "support_tier": "tier-1-contract-gate" if provider_id in tier1 else ("compatibility-spike" if provider_id in special else "tier-2-certification"),
            "risk": special.get(provider_id, "provider endpoint/model availability must be certified outside deterministic CI"),
            "credential_fixture": "${API_KEY}",
        })
    if len(providers) != 24:
        raise RuntimeError(f"Expected 24 public provider presets, found {len(providers)}")

    write_json(OUT / "provider-migration.json", {
        "schema_version": "1.0",
        "source": "src/features/settings/providerMap.ts",
        "decision_policy": "Preserve every public preset during migration; certify wire compatibility by tier. No provider is silently removed.",
        "providers": providers,
    })

    write_json(OUT / "openai-compatible.fixture.json", {
        "schema_version": "1.0",
        "protocol": "openai",
        "request": {
            "method": "POST",
            "path_suffix": "/chat/completions",
            "headers": {"Authorization": "Bearer ${API_KEY}", "Content-Type": "application/json"},
            "body": {
                "model": "${MODEL}",
                "messages": [{"role": "system", "content": "You are OpenGIS."}, {"role": "user", "content": "List layers."}],
                "tools": [{"type": "function", "function": {"name": "list_layers", "description": "List map layers", "parameters": {"type": "object", "properties": {}}}}],
                "tool_choice": "auto",
                "stream": True,
            },
        },
        "stream_examples": [
            {"choices": [{"delta": {"role": "assistant", "content": None}, "finish_reason": None}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_fixture", "type": "function", "function": {"name": "list_layers", "arguments": "{}"}}]}, "finish_reason": None}]},
            {"choices": [{"delta": {}, "finish_reason": "tool_calls"}], "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}},
        ],
        "normalized_expectation": {"content": None, "tool_calls": [{"id": "call_fixture", "name": "list_layers", "arguments": {}}], "finish_reason": "tool_calls"},
    })

    write_json(OUT / "anthropic-compatible.fixture.json", {
        "schema_version": "1.0",
        "protocol": "anthropic",
        "request": {
            "method": "POST",
            "path_suffix": "/v1/messages",
            "headers": {"x-api-key": "${API_KEY}", "anthropic-version": "${ANTHROPIC_VERSION}", "Content-Type": "application/json"},
            "body": {
                "model": "${MODEL}",
                "system": "You are OpenGIS.",
                "messages": [{"role": "user", "content": "List layers."}],
                "tools": [{"name": "list_layers", "description": "List map layers", "input_schema": {"type": "object", "properties": {}}}],
                "max_tokens": 1024,
                "stream": True,
            },
        },
        "stream_examples": [
            {"type": "message_start", "message": {"id": "msg_fixture", "role": "assistant", "usage": {"input_tokens": 10, "output_tokens": 0}}},
            {"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "tool_fixture", "name": "list_layers", "input": {}}},
            {"type": "content_block_stop", "index": 0},
            {"type": "message_delta", "delta": {"stop_reason": "tool_use"}, "usage": {"output_tokens": 4}},
            {"type": "message_stop"},
        ],
        "normalized_expectation": {"content": None, "tool_calls": [{"id": "tool_fixture", "name": "list_layers", "arguments": {}}], "finish_reason": "tool_calls"},
    })
    print(json.dumps({"providers": len(providers), "openai": sum(p["current_protocol"] == "openai" for p in providers), "anthropic": sum(p["current_protocol"] == "anthropic" for p in providers)}))


if __name__ == "__main__":
    main()
