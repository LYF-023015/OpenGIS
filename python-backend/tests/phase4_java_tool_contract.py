"""Validate Java rpc.tool.list output against the live Python registry.

This script is intentionally invoked only through python-backend/.venv by the
Java Phase 4 integration test.
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

from opengis_backend.tools.registry import ToolRegistry


async def main() -> int:
    catalog_path = Path(sys.argv[1])
    payload = json.loads(catalog_path.read_text(encoding="utf-8"))
    tools = payload.get("tools")
    if not isinstance(tools, list) or not tools:
        raise AssertionError("Java catalog must contain tools")

    registry = ToolRegistry()
    await registry.discover_and_load()
    python_names = {schema.name for schema in registry.list_all()}
    java_names: set[str] = set()
    for tool in tools:
        required = {
            "name",
            "display_name",
            "description",
            "category",
            "params",
            "returns",
            "tags",
            "version",
            "input_schema",
        }
        missing = required.difference(tool)
        if missing:
            raise AssertionError(f"{tool.get('name')}: missing {sorted(missing)}")
        name = tool["name"]
        if name not in python_names:
            raise AssertionError(f"Java tool is absent from Python registry: {name}")
        if name in java_names:
            raise AssertionError(f"Duplicate Java tool: {name}")
        java_names.add(name)
        schema = tool["input_schema"]
        if schema.get("type") != "object" or not isinstance(schema.get("properties"), dict):
            raise AssertionError(f"{name}: invalid JSON Schema")
        params = {param["name"] for param in tool["params"]}
        if params != set(schema["properties"]):
            raise AssertionError(f"{name}: params/input_schema mismatch")

    essential = {
        "read_file",
        "write_file",
        "bash",
        "webfetch",
        "add_layer",
        "layout_get_state",
        "write_report_section",
        "save_plot",
        "academic_polish",
        "debug_agent_context",
        "list_scripts",
        "load_skill",
        "csv_to_geojson",
    }
    missing_essential = essential.difference(java_names)
    if missing_essential:
        raise AssertionError(f"Missing Phase 4 categories: {sorted(missing_essential)}")

    print(json.dumps({"status": "ok", "java_tools": len(java_names), "python_tools": len(python_names)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
