"""Validate that Phase 0 assets are readable by the current Python implementation."""

from __future__ import annotations

import json
import gzip
from pathlib import Path

import geopandas as gpd
import rasterio
from jsonschema import Draft202012Validator

from opengis_backend.agent.governance.permission_store import PermissionRuleStore
from opengis_backend.agent.governance.profile import AgentProfileStore
from opengis_backend.agent.session.session import SessionStore
from opengis_backend.agent.workflow.workflow_store import WorkflowDocumentStore
from opengis_backend.runs.archive import RunArchive


REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / "test" / "phase0"
WORKSPACE = ROOT / "fixtures" / "opengis-workspace"


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> None:
    inventory = json.loads((ROOT / "rpc" / "method-inventory.json").read_text(encoding="utf-8"))
    assert inventory["backend_request_method_count"] == len(inventory["backend_request_methods"])
    assert inventory["frontend_inbound_method_count"] == len(inventory["frontend_inbound_methods"])
    contracts = json.loads((ROOT / "rpc" / "rpc-method-contracts.json").read_text(encoding="utf-8"))
    assert contracts["method_count"] == inventory["backend_request_method_count"]
    assert {item["method"] for item in contracts["methods"]} == set(inventory["backend_request_methods"])

    transcripts = read_jsonl(ROOT / "rpc" / "protocol-transcripts.jsonl")
    assert len(transcripts) == 20
    schema = json.loads((ROOT / "rpc" / "json-rpc-envelope.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)

    golden = json.loads((ROOT / "golden" / "python" / "gis-and-operations-summary.json").read_text(encoding="utf-8"))
    for summary in golden["vectors"]:
        dataset = gpd.read_file(REPO / summary["file"])
        assert len(dataset) == summary["feature_count"], summary["file"]
    raster_summary = golden["raster"]
    with rasterio.open(REPO / raster_summary["file"]) as dataset:
        assert dataset.width == raster_summary["width"]
        assert dataset.height == raster_summary["height"]
        assert str(dataset.crs) == raster_summary["crs"]
    for summary in golden["scale_vectors"]:
        raw = gzip.decompress((REPO / summary["file"]).read_bytes())
        payload = json.loads(raw)
        assert len(payload["features"]) == summary["feature_count"]

    sessions = SessionStore(str(WORKSPACE)).list_recent()
    inbox = SessionStore(str(WORKSPACE)).list_inbox()
    profiles = AgentProfileStore(str(WORKSPACE)).load_all()
    rules = PermissionRuleStore(str(WORKSPACE)).list_rules()
    workflow = WorkflowDocumentStore(str(WORKSPACE)).load(workflow_id="workflow-phase0")
    archive = RunArchive.load(str(WORKSPACE), "run-phase0-001")
    assert len(sessions) == 1
    assert len(inbox) == 1
    assert "gis-build" in profiles
    assert len(rules) == 1
    assert workflow is not None and len(workflow.nodes) == 2
    assert archive is not None and archive.meta["status"] == "completed"
    assert len(archive.read_events()) == 2

    schema_inventory = json.loads((WORKSPACE / ".opengis" / "schema-inventory.json").read_text(encoding="utf-8"))
    for store in schema_inventory["stores"]:
        fixture = store.get("fixture")
        if fixture is None or "*" in fixture:
            continue
        assert (WORKSPACE / fixture).exists(), store
    for path in (WORKSPACE / ".opengis" / "memory").glob("*.jsonl"):
        assert read_jsonl(path), path

    forbidden = ("C:\\Users\\", "/Users/", "api_key", "sk-")
    for path in WORKSPACE.rglob("*"):
        if not path.is_file() or path.suffix.lower() in {".shp", ".shx", ".dbf", ".gpkg", ".tif"}:
            continue
        text = path.read_text(encoding="utf-8")
        assert not any(marker in text for marker in forbidden), f"Non-anonymous marker in {path}"

    print(json.dumps({
        "status": "ok",
        "backend_methods": inventory["backend_request_method_count"],
        "frontend_methods": inventory["frontend_inbound_method_count"],
        "transcripts": len(transcripts),
        "vector_fixtures": len(golden["vectors"]),
        "scale_vector_fixtures": len(golden["scale_vectors"]),
        "rpc_method_contracts": contracts["method_count"],
        "opengis_store_schemas": len(schema_inventory["stores"]),
        "sessions": len(sessions),
        "workflows": 1,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
