"""Export the Phase 0 OpenGIS transport inventory and deterministic transcripts."""

from __future__ import annotations

import ast
import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "test" / "phase0" / "rpc"
HANDLER = REPO / "python-backend" / "opengis_backend" / "rpc" / "handler.py"
SERVER = REPO / "python-backend" / "opengis_backend" / "server.py"
FRONTEND_HANDLERS = REPO / "src" / "services" / "rpc" / "handlers"


def backend_methods() -> list[str]:
    tree = ast.parse(HANDLER.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            if any(isinstance(target, ast.Attribute) and target.attr == "_method_handlers" for target in node.targets):
                if not isinstance(node.value, ast.Dict):
                    continue
                return sorted(
                    key.value
                    for key in node.value.keys
                    if isinstance(key, ast.Constant) and isinstance(key.value, str)
                )
    raise RuntimeError("RpcHandler._method_handlers was not found")


def backend_method_bindings(tree: ast.AST) -> dict[str, str]:
    """Return the literal JSON-RPC method -> handler function binding."""
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        if not any(isinstance(target, ast.Attribute) and target.attr == "_method_handlers" for target in node.targets):
            continue
        if not isinstance(node.value, ast.Dict):
            continue
        bindings: dict[str, str] = {}
        for key, value in zip(node.value.keys, node.value.values, strict=True):
            if (
                isinstance(key, ast.Constant)
                and isinstance(key.value, str)
                and isinstance(value, ast.Attribute)
            ):
                bindings[key.value] = value.attr
        return dict(sorted(bindings.items()))
    raise RuntimeError("RpcHandler._method_handlers bindings were not found")


def _matcher(node: ast.AST | None, *, field: str = "") -> object:
    if node is not None:
        try:
            return ast.literal_eval(node)
        except (ValueError, TypeError):
            pass
    lowered = field.lower()
    if lowered == "workspace_path":
        return "${WORKSPACE}"
    if lowered.endswith("_path") or lowered in {"path", "file", "filename"}:
        return "${WORKSPACE}/fixture"
    if lowered in {"limit", "offset", "max_steps", "line_start", "line_end"}:
        return 20
    if lowered.startswith("include_") or lowered.startswith("force"):
        return False
    if lowered.endswith("_id") or lowered in {"id", "run_id", "session_id", "worker_id"}:
        return f"phase0-{lowered.replace('_', '-')}"
    return {"$type": "string"}


def _result_matcher(node: ast.AST, field: str) -> object:
    try:
        value = ast.literal_eval(node)
        if value is None:
            return {"$type": "any"}
        return value
    except (ValueError, TypeError):
        pass
    if isinstance(node, (ast.List, ast.ListComp)):
        return {"$type": "array"}
    if isinstance(node, (ast.Dict, ast.DictComp)):
        return {"$type": "object"}
    if isinstance(node, (ast.Compare, ast.BoolOp)):
        return {"$type": "boolean"}
    if field.endswith("s") or field in {"items", "workers", "tools", "runs", "sessions"}:
        return {"$type": "array"}
    return {"$type": "any"}


def method_contracts(tree: ast.AST) -> list[dict[str, object]]:
    """Build a conservative, source-derived contract portrait for every method.

    The values are examples/matchers rather than a claim that the legacy code
    already owns complete JSON Schemas. Required keys come from ``params[key]``;
    optional keys/defaults come from ``params.get(key, default)``.
    """
    bindings = backend_method_bindings(tree)
    functions = {
        node.name: node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    }
    contracts: list[dict[str, object]] = []
    for method, handler_name in bindings.items():
        function = functions.get(handler_name)
        if function is None:
            raise RuntimeError(f"Handler function not found: {handler_name}")
        required: set[str] = set()
        optional_defaults: dict[str, ast.AST | None] = {}
        result_fields: dict[str, object] = {}
        returns_non_object = False
        for node in ast.walk(function):
            if (
                isinstance(node, ast.Subscript)
                and isinstance(node.value, ast.Name)
                and node.value.id == "params"
                and isinstance(node.slice, ast.Constant)
                and isinstance(node.slice.value, str)
                and isinstance(node.ctx, ast.Load)
            ):
                required.add(node.slice.value)
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "params"
                and node.func.attr == "get"
                and node.args
                and isinstance(node.args[0], ast.Constant)
                and isinstance(node.args[0].value, str)
            ):
                optional_defaults[node.args[0].value] = node.args[1] if len(node.args) > 1 else None
            if isinstance(node, ast.Return) and node.value is not None:
                if isinstance(node.value, ast.Dict):
                    for key, value in zip(node.value.keys, node.value.values, strict=True):
                        if isinstance(key, ast.Constant) and isinstance(key.value, str):
                            result_fields.setdefault(key.value, _result_matcher(value, key.value))
                else:
                    returns_non_object = True
        for key in required:
            optional_defaults.pop(key, None)
        params_example = {key: _matcher(None, field=key) for key in sorted(required)}
        params_example.update(
            {key: _matcher(default, field=key) for key, default in sorted(optional_defaults.items())}
        )
        contracts.append({
            "method": method,
            "handler": handler_name,
            "source_line": function.lineno,
            "params": {
                "required": sorted(required),
                "optional": [
                    {"name": key, "default": _matcher(default, field=key)}
                    for key, default in sorted(optional_defaults.items())
                ],
                "example": params_example,
            },
            "result": {
                "example_or_matcher": result_fields or {"$type": "any"},
                "may_be_non_object": returns_non_object,
            },
            "json_rpc_error_codes": [-32602, -32603],
            "error_notes": {
                "-32602": "ValueError or missing required dictionary field",
                "-32603": "unhandled handler exception",
            },
            "extraction": "conservative AST portrait; Phase 2 must replace with shared method-specific JSON Schemas",
        })
    return contracts


def frontend_methods() -> list[str]:
    found: set[str] = set()
    pattern = re.compile(r"^\s*['\"]((?:rpc|chat|event)\.[^'\"]+)['\"]\s*:", re.MULTILINE)
    for path in FRONTEND_HANDLERS.rglob("*.ts"):
        found.update(pattern.findall(path.read_text(encoding="utf-8")))
    return sorted(found)


def rest_endpoints() -> list[dict[str, str]]:
    pattern = re.compile(r'^@app\.(get|post|websocket)\("([^\"]+)"\)', re.MULTILINE)
    return [
        {"method": method.upper(), "path": path}
        for method, path in pattern.findall(SERVER.read_text(encoding="utf-8"))
    ]


def observed_server_push_methods(request_methods: list[str]) -> list[str]:
    literal = re.compile(r"['\"]((?:rpc|chat|event)\.[A-Za-z0-9_.-]+)['\"]")
    found: set[str] = set()
    roots = [
        REPO / "python-backend" / "opengis_backend" / "agent",
        REPO / "python-backend" / "opengis_backend" / "tools",
        REPO / "python-backend" / "opengis_backend" / "worker",
        REPO / "python-backend" / "opengis_backend" / "sandbox",
    ]
    for root in roots:
        for path in root.rglob("*.py"):
            found.update(literal.findall(path.read_text(encoding="utf-8")))
    normalized = {f"{item}*" if item.endswith(".") else item for item in found}
    return sorted(normalized.difference(request_methods))


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def transcript_rows() -> list[dict[str, object]]:
    return [
        {"name": "health", "transport": "http", "request": {"method": "GET", "path": "/api/health"}, "expect": {"status": 200, "json": {"status": "ok", "version": "0.1.0"}}},
        {"name": "invalid-ws-token", "transport": "websocket", "request": {"path": "/ws?token=invalid"}, "expect": {"error": {"code": -32001}, "close": True}},
        {"name": "parse-error", "transport": "websocket", "request_raw": "{", "expect": {"error": {"code": -32700}, "id": None}},
        {"name": "invalid-version", "transport": "websocket", "request": {"id": "t-01", "method": "rpc.debug.get_log_level", "params": {}}, "expect": {"error": {"code": -32600}, "id": "t-01"}},
        {"name": "missing-method", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-02", "params": {}}, "expect": {"error": {"code": -32600}, "id": "t-02"}},
        {"name": "unknown-method", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-03", "method": "rpc.unknown", "params": {}}, "expect": {"error": {"code": -32601}, "id": "t-03"}},
        {"name": "debug-level", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-04", "method": "rpc.debug.get_log_level", "params": {}}, "expect": {"id": "t-04", "result": {"level": {"$type": "string"}}}},
        {"name": "tool-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-05", "method": "rpc.tool.list", "params": {}}, "expect": {"id": "t-05", "result": {"tools": {"$type": "array"}}}},
        {"name": "runs-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-06", "method": "rpc.runs.list", "params": {"workspace_path": "${WORKSPACE}", "limit": 20}}, "expect": {"id": "t-06", "result": {"runs": {"$type": "array"}}}},
        {"name": "profiles-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-07", "method": "rpc.agent.profiles.list", "params": {"workspace_path": "${WORKSPACE}"}}, "expect": {"id": "t-07", "result": {"profiles": {"$type": "array"}}}},
        {"name": "sessions-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-08", "method": "rpc.agent.sessions.list", "params": {"workspace_path": "${WORKSPACE}", "limit": 20}}, "expect": {"id": "t-08", "result": {"sessions": {"$type": "array"}}}},
        {"name": "permissions-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-09", "method": "rpc.agent.permissions.list", "params": {"workspace_path": "${WORKSPACE}"}}, "expect": {"id": "t-09", "result": {"requests": {"$type": "array"}}}},
        {"name": "operations-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-10", "method": "rpc.operations.list", "params": {"workspace_path": "${WORKSPACE}"}}, "expect": {"id": "t-10", "result": {"operations": {"$type": "array"}}}},
        {"name": "workers-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-11", "method": "rpc.worker.list", "params": {"workspace_path": "${WORKSPACE}"}}, "expect": {"id": "t-11", "result": {"workers": {"$type": "array"}}}},
        {"name": "user-skill-list", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-12", "method": "rpc.user_skill.list", "params": {"workspace_path": "${WORKSPACE}"}}, "expect": {"id": "t-12", "result": {"skills": {"$type": "array"}}}},
        {"name": "invalid-tool-execute", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-13", "method": "rpc.tool.execute", "params": {}}, "expect": {"id": "t-13", "error": {"code": -32602}}},
        {"name": "invalid-run-get", "transport": "websocket", "request": {"jsonrpc": "2.0", "id": "t-14", "method": "rpc.runs.get", "params": {"workspace_path": "${WORKSPACE}"}}, "expect": {"id": "t-14", "error": {"code": -32602}}},
        {"name": "ui-add-layer-notification", "transport": "websocket", "direction": "backend-to-ui", "message": {"jsonrpc": "2.0", "method": "rpc.ui.map.add_layer_from_geojson", "params": {"layer_id": "phase0-layer", "name": "Phase 0", "geojson": {"type": "FeatureCollection", "features": []}}}, "expect": {"no_response": True}},
        {"name": "chat-message-part-notification", "transport": "websocket", "direction": "backend-to-ui", "message": {"jsonrpc": "2.0", "method": "chat.message_part", "params": {"conversation_id": "conv-phase0", "part": {"id": "part-phase0", "type": "text", "status": "done", "content": "baseline"}}}, "expect": {"no_response": True}},
        {"name": "dynamic-full-then-diff", "transport": "websocket", "direction": "backend-to-ui", "messages": [{"jsonrpc": "2.0", "method": "rpc.ui.map.dynamic_layer_update", "params": {"layer_id": "live", "mode": "full", "sequence": 1, "geojson": {"type": "FeatureCollection", "features": []}}}, {"jsonrpc": "2.0", "method": "rpc.ui.map.dynamic_layer_update", "params": {"layer_id": "live", "mode": "diff", "sequence": 2, "diff": {"add": [], "update": [], "remove": []}}}], "expect": {"delivery_order": [1, 2]}}
    ]


def main() -> None:
    tree = ast.parse(HANDLER.read_text(encoding="utf-8"))
    contracts = method_contracts(tree)
    outbound = [contract["method"] for contract in contracts]
    inbound = frontend_methods()
    inventory = {
        "schema_version": "1.0",
        "protocol_version": "3.0",
        "jsonrpc_version": "2.0",
        "backend_request_method_count": len(outbound),
        "backend_request_methods": outbound,
        "frontend_inbound_method_count": len(inbound),
        "frontend_inbound_methods": inbound,
        "observed_server_push_methods": observed_server_push_methods(outbound),
        "rest_and_websocket_endpoints": rest_endpoints(),
        "startup_stdout_order": ["OPENGIS_WS_TOKEN=<random>", "OPENGIS_READY"],
        "method_contract_file": "test/phase0/rpc/rpc-method-contracts.json",
        "global_error_codes": {
            "-32700": "Parse error",
            "-32600": "Invalid Request",
            "-32601": "Method not found",
            "-32602": "Invalid params",
            "-32603": "Internal error",
            "-32001": "Invalid WebSocket authentication token",
        },
    }
    write_json(OUT / "method-inventory.json", inventory)
    write_json(OUT / "rpc-method-contracts.json", {
        "schema_version": "1.0",
        "source": "python-backend/opengis_backend/rpc/handler.py",
        "method_count": len(contracts),
        "methods": contracts,
    })

    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://opengis.local/schema/json-rpc-envelope.json",
        "title": "OpenGIS JSON-RPC 2.0 envelope",
        "oneOf": [
            {"$ref": "#/$defs/request"},
            {"$ref": "#/$defs/success"},
            {"$ref": "#/$defs/error"},
        ],
        "$defs": {
            "request": {"type": "object", "required": ["jsonrpc", "method"], "properties": {"jsonrpc": {"const": "2.0"}, "id": {"type": ["string", "integer", "null"]}, "method": {"type": "string", "minLength": 1}, "params": {"type": ["object", "array"]}}, "additionalProperties": False},
            "success": {"type": "object", "required": ["jsonrpc", "id", "result"], "properties": {"jsonrpc": {"const": "2.0"}, "id": {"type": ["string", "integer", "null"]}, "result": {}}, "additionalProperties": False},
            "errorObject": {"type": "object", "required": ["code", "message"], "properties": {"code": {"type": "integer"}, "message": {"type": "string"}, "data": {}}, "additionalProperties": False},
            "error": {"type": "object", "required": ["jsonrpc", "id", "error"], "properties": {"jsonrpc": {"const": "2.0"}, "id": {"type": ["string", "integer", "null"]}, "error": {"$ref": "#/$defs/errorObject"}}, "additionalProperties": False},
        },
    }
    write_json(OUT / "json-rpc-envelope.schema.json", schema)
    (OUT / "protocol-transcripts.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in transcript_rows()),
        encoding="utf-8",
    )
    print(json.dumps({"inventory": str(OUT / 'method-inventory.json'), "transcripts": len(transcript_rows())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
