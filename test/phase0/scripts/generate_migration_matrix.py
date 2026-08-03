"""Generate the exhaustive Phase 0 migration matrix from captured inventories."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import yaml

from opengis_backend.tools.registry import ToolRegistry


REPO = Path(__file__).resolve().parents[3]
PHASE0 = REPO / "test" / "phase0"
OUT = REPO / "docs" / "migration" / "migration-matrix.yaml"


def entry(identifier: str, category: str, source: str, consumer: str, target: str, verification: str, phase: int | str, decision: str = "migrate") -> dict[str, Any]:
    return {"id": identifier, "category": category, "source": source, "consumer": consumer, "decision": decision, "java_target": target, "phase": phase, "verification": verification}


def backend_target(method: str) -> str:
    if method.startswith("chat.") or method.startswith("rpc.agent.") or method.startswith("rpc.runs."):
        return "opengis-agent"
    if method.startswith("rpc.tool."):
        return "opengis-tool"
    if method.startswith("rpc.user_skill."):
        return "opengis-knowledge"
    if method.startswith("rpc.operations."):
        return "opengis-gis"
    if method.startswith("rpc.worker."):
        return "opengis-worker"
    if method.startswith("rpc.fs.") or method.startswith("rpc.workspace.") or method.startswith("user_instructions."):
        return "opengis-platform"
    if method.startswith("rpc.code."):
        return "opengis-platform"
    return "opengis-server"


def tool_target(name: str, category: str, tags: list[str]) -> str:
    words = {name, category, *tags}
    joined = " ".join(words).lower()
    if any(token in joined for token in ("worker", "dynamic map")):
        return "opengis-worker"
    if any(token in joined for token in ("workflow", "flow")):
        return "opengis-workflow"
    if any(token in joined for token in ("map", "raster", "geo", "gis", "qgis", "osm", "datasource", "operation")):
        return "opengis-gis"
    if any(token in joined for token in ("subagent", "agent context")):
        return "opengis-agent"
    return "opengis-tool"


def storage_target(name: str) -> str:
    if name in {"sessions-and-inbox", "agent-profiles", "artifact-index", "run-archive"}:
        return "opengis-agent"
    if name in {"conversation-context", "structured-memory", "legacy-memory", "workspace-skills", "skill-sources"}:
        return "opengis-knowledge"
    if name in {"workflows", "workflow-step-output"}:
        return "opengis-workflow"
    if name == "permissions":
        return "opengis-tool"
    if name in {"workspace-operations", "operation-runs", "raster-cache"}:
        return "opengis-gis"
    return "opengis-platform"


async def tools() -> list[dict[str, Any]]:
    registry = ToolRegistry()
    await registry.discover_and_load()
    return [schema.to_dict() for schema in registry.list_all()]


def main() -> None:
    rpc = json.loads((PHASE0 / "rpc" / "method-inventory.json").read_text(encoding="utf-8"))
    providers = json.loads((PHASE0 / "providers" / "provider-migration.json").read_text(encoding="utf-8"))
    consumers = json.loads((PHASE0 / "baseline" / "python-semantic-consumers.json").read_text(encoding="utf-8"))
    storage_inventory = json.loads((PHASE0 / "fixtures" / "opengis-workspace" / ".opengis" / "schema-inventory.json").read_text(encoding="utf-8"))
    rows: list[dict[str, Any]] = []

    for endpoint in rpc["rest_and_websocket_endpoints"]:
        rows.append(entry(f"transport:{endpoint['method']}:{endpoint['path']}", "rest-or-websocket", "python-backend/opengis_backend/server.py", "opengis-ui/external client", "opengis-server", "HTTP/WebSocket contract transcript", 2))
    for method in rpc["backend_request_methods"]:
        rows.append(entry(f"rpc-in:{method}", "renderer-to-backend-rpc", "python-backend/opengis_backend/rpc/handler.py", "src/services/pythonClient.ts", backend_target(method), "protocol transcript + method-specific contract test", 2 if backend_target(method) == "opengis-server" else "owning-module"))
    for method in rpc["frontend_inbound_methods"]:
        rows.append(entry(f"rpc-out:{method}", "backend-to-renderer-rpc", "python Tool/Agent/Worker notification", "src/services/rpc/handlers", "opengis-common UiRpcPort + opengis-ui handler", "TypeScript handler test + server-push transcript", 2))
    for method in rpc["observed_server_push_methods"]:
        rows.append(entry(f"event:{method}", "event-or-notification", "python-backend/opengis_backend", "src/services/pythonClient.ts subscribers", "opengis-common event contract", "JSON envelope + MessagePart/event projection test", 2))

    for schema in asyncio.run(tools()):
        rows.append(entry(f"tool:{schema['name']}", "tool", "python-backend/opengis_backend/tools or integrations", "Agent ToolRuntime", tool_target(schema["name"], schema.get("category", ""), schema.get("tags", [])), "tool schema + success/invalid/deny/cancel/error tests", 4 if tool_target(schema["name"], schema.get("category", ""), schema.get("tags", [])) == "opengis-tool" else "owning-module"))

    for store in storage_inventory["stores"]:
        verification = "regenerate cache from source fixture" if store.get("classification") == "regenerable-cache" else "anonymous fixture + Python/Java round-trip"
        rows.append(entry(f"storage:{store['name']}", "storage", store["path_pattern"], "workspace/user", storage_target(store["name"]), verification, 3))
    rows.append(entry("storage:user-instructions", "storage", "~/.opengis/user_instructions.md", "global user preferences", "opengis-platform", "temporary-home anonymous fixture + Python/Java round-trip", 3))

    for provider in providers["providers"]:
        rows.append(entry(f"provider:{provider['id']}", "llm-provider", "src/features/settings/providerMap.ts", "Settings + Agent LLM caller", "opengis-ai", f"{provider['current_protocol']} fixture + {provider['support_tier']}", 5, provider["decision"]))

    for consumer in consumers["consumers"]:
        rows.append(entry(f"python-consumer:{consumer['file']}", "python-semantic-consumer", consumer["file"], consumer["category"], consumer["java_target"], consumer["replacement"], 9 if consumer["category"] in {"electron-launcher", "documentation"} else "owning-module"))

    external = [
        ("python-interpreter", "Python >=3.11 and project venv", "opengis-server bundled JRE", 10),
        ("pip", "pip/requirements dynamic installation", "opengis-platform Maven Resolver", 8),
        ("generated-code", "Python subprocess executor", "opengis-platform isolated Java runner", 8),
        ("resident-worker", "Python Worker process", "opengis-worker child JVM", 8),
        ("qgis", "QGIS MCP localhost service", "opengis-gis QGIS adapter", 7),
        ("osm", "Nominatim/Overpass", "opengis-gis OSM adapter", 7),
        ("native-gis", "GDAL/PROJ wheels", "opengis-gis GeoTools/JTS/optional GDAL JNI", 7),
    ]
    for name, source, target, phase in external:
        rows.append(entry(f"runtime:{name}", "external-runtime", source, "backend", target, "platform-specific integration/packaging test", phase))

    user_assets = ["local-gis-files", "workspace-python-operation", "workspace-python-worker", "user-skill", "workflow", "run-history", "model-preset"]
    for asset in user_assets:
        rows.append(entry(f"user-asset:{asset}", "user-asset", "user workspace/settings", "OpenGIS user", "owning module + migration inspector", "backup, inspect, migrate, rollback fixture", "owning-module"))

    payload = {
        "schema_version": "1.0",
        "generated_from": ["test/phase0/rpc/method-inventory.json", "test/phase0/providers/provider-migration.json", "test/phase0/baseline/python-semantic-consumers.json", "test/phase0/fixtures/opengis-workspace/.opengis/schema-inventory.json", "runtime ToolRegistry"],
        "entry_count": len(rows),
        "entries": rows,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(yaml.safe_dump(payload, allow_unicode=True, sort_keys=False, width=140), encoding="utf-8")
    print(json.dumps({"entries": len(rows), "output": str(OUT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
