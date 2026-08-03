"""Generate deterministic GIS, Operation and synthetic .opengis Phase 0 fixtures."""

from __future__ import annotations

import importlib.util
import gzip
import hashlib
import io
import json
import math
import shutil
from pathlib import Path
from typing import Any

import geopandas as gpd
import numpy as np
import pandas as pd
import rasterio
from rasterio.transform import from_origin
from shapely.geometry import LineString, Point, Polygon, mapping


REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / "test" / "phase0" / "fixtures"
GIS = ROOT / "gis"
OPENGIS = ROOT / "opengis-workspace"
GOLDEN = REPO / "test" / "phase0" / "golden" / "python"


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")


def reset_generated_directory(path: Path) -> None:
    """Delete only a generated child directory below the fixed Phase 0 fixture root."""
    resolved = path.resolve()
    root = ROOT.resolve()
    if resolved == root or root not in resolved.parents:
        raise RuntimeError(f"Refusing to reset path outside Phase 0 fixtures: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)


def feature_collection(geometries: list[Any], properties: list[dict[str, Any]], crs: str = "EPSG:4326") -> dict[str, Any]:
    return {
        "type": "FeatureCollection",
        "name": "phase0-fixture",
        "crs": {"type": "name", "properties": {"name": crs}},
        "features": [
            {"type": "Feature", "id": index + 1, "properties": props, "geometry": mapping(geometry) if geometry is not None else None}
            for index, (geometry, props) in enumerate(zip(geometries, properties, strict=True))
        ],
    }


def create_gis_fixtures() -> None:
    GIS.mkdir(parents=True, exist_ok=True)
    points = [
        Point(116.391, 39.907), Point(121.474, 31.230), Point(113.264, 23.129),
        Point(114.057, 22.543), Point(104.066, 30.572), Point(87.617, 43.826),
    ]
    point_props = [
        {"name": "北京", "category": "capital", "value": 100.0, "weight": 2.0},
        {"name": "上海", "category": "municipality", "value": 90.5, "weight": 1.8},
        {"name": "广州", "category": "city", "value": 70.0, "weight": 1.2},
        {"name": "深圳", "category": "city", "value": 80.0, "weight": 1.4},
        {"name": "成都", "category": "city", "value": 60.5, "weight": 1.0},
        {"name": "乌鲁木齐", "category": None, "value": 40.0, "weight": 0.8},
    ]
    write_json(GIS / "points_wgs84.geojson", feature_collection(points, point_props))

    lines = [
        LineString([(116.2, 39.8), (116.6, 40.0), (117.0, 39.9)]),
        LineString([(121.2, 31.0), (121.5, 31.3), (121.8, 31.1)]),
    ]
    write_json(GIS / "lines_wgs84.geojson", feature_collection(lines, [{"name": "route-a", "class": 1}, {"name": "route-b", "class": 2}]))

    polygons = [
        Polygon([(116.2, 39.7), (116.8, 39.7), (116.8, 40.1), (116.2, 40.1), (116.2, 39.7)]),
        Polygon([(121.1, 30.9), (121.9, 30.9), (121.9, 31.5), (121.1, 31.5), (121.1, 30.9)]),
    ]
    write_json(GIS / "polygons_wgs84.geojson", feature_collection(polygons, [{"name": "area-a", "population": 10}, {"name": "area-b", "population": 20}]))

    invalid = Polygon([(0, 0), (1, 1), (1, 0), (0, 1), (0, 0)])
    edge_geometries = [Point(0, 0), None, invalid, Point(179.9, 85.0)]
    edge_props = [
        {"name": "origin", "unicode": "中文", "nullable": None},
        {"name": "null-geometry", "unicode": "空几何", "nullable": None},
        {"name": "self-intersection", "unicode": "无效面", "nullable": 1},
        {"name": "edge", "unicode": "日期变更线", "nullable": 2},
    ]
    write_json(GIS / "edge_cases_wgs84.geojson", feature_collection(edge_geometries, edge_props))

    pd.DataFrame([
        {"name": props["name"], "longitude": point.x, "latitude": point.y, "value": props["value"], "中文字段": "样本"}
        for point, props in zip(points, point_props, strict=True)
    ]).to_csv(GIS / "points_wgs84.csv", index=False, encoding="utf-8-sig")

    point_gdf = gpd.GeoDataFrame(point_props, geometry=points, crs="EPSG:4326")
    shapefile_dir = GIS / "points_shapefile"
    reset_generated_directory(shapefile_dir)
    shapefile_dir.mkdir()
    point_gdf.to_file(shapefile_dir / "points.shp", driver="ESRI Shapefile", encoding="UTF-8")
    point_gdf.to_file(GIS / "points.gpkg", layer="points", driver="GPKG")

    raster_path = GIS / "small_raster_3857.tif"
    raster = np.arange(1024, dtype="float32").reshape(32, 32)
    raster[0, 0] = -9999.0
    with rasterio.open(
        raster_path,
        "w",
        driver="GTiff",
        height=32,
        width=32,
        count=1,
        dtype="float32",
        crs="EPSG:3857",
        transform=from_origin(-20037508.3427892, 20037508.3427892, 1252344.2714243, 1252344.2714243),
        nodata=-9999.0,
    ) as dataset:
        dataset.write(raster, 1)

    scale_dir = GIS / "scale"
    reset_generated_directory(scale_dir)
    scale_dir.mkdir()
    for count in (10_000, 100_000):
        features = [
            {
                "type": "Feature",
                "id": index,
                "properties": {"value": index},
                "geometry": {
                    "type": "Point",
                    "coordinates": [
                        100.0 + (index % 1000) * 0.001,
                        20.0 + (index // 1000) * 0.001,
                    ],
                },
            }
            for index in range(count)
        ]
        raw = json.dumps(
            {"type": "FeatureCollection", "features": features},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        buffer = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=buffer, mtime=0) as compressed:
            compressed.write(raw)
        (scale_dir / f"points_{count}.geojson.gz").write_bytes(buffer.getvalue())


def vector_summary(path: Path) -> dict[str, Any]:
    data = gpd.read_file(path)
    valid = data.geometry.dropna().is_valid
    bounds = [round(float(value), 6) for value in data.total_bounds] if len(data) else None
    return {
        "file": path.relative_to(REPO).as_posix(),
        "feature_count": len(data),
        "geometry_types": sorted(set(data.geometry.geom_type.dropna().tolist())),
        "null_geometry_count": int(data.geometry.isna().sum()),
        "valid_geometry_count": int(valid.sum()),
        "invalid_geometry_count": int((~valid).sum()),
        "crs": str(data.crs) if data.crs else None,
        "bbox": bounds,
        "fields": [name for name in data.columns if name != data.geometry.name],
    }


def raster_summary(path: Path) -> dict[str, Any]:
    with rasterio.open(path) as dataset:
        band = dataset.read(1, masked=True)
        return {
            "file": path.relative_to(REPO).as_posix(),
            "driver": dataset.driver,
            "width": dataset.width,
            "height": dataset.height,
            "count": dataset.count,
            "dtype": dataset.dtypes[0],
            "crs": str(dataset.crs),
            "nodata": dataset.nodata,
            "bounds": [round(float(value), 4) for value in dataset.bounds],
            "min": float(band.min()),
            "max": float(band.max()),
            "mean": round(float(band.mean()), 6),
            "valid_pixel_count": int(band.count()),
        }


def scale_summary(path: Path) -> dict[str, Any]:
    compressed = path.read_bytes()
    raw = gzip.decompress(compressed)
    data = json.loads(raw)
    coordinates = [feature["geometry"]["coordinates"] for feature in data["features"]]
    xs = [value[0] for value in coordinates]
    ys = [value[1] for value in coordinates]
    return {
        "file": path.relative_to(REPO).as_posix(),
        "compression": "gzip with mtime=0",
        "feature_count": len(data["features"]),
        "geometry_type": "Point",
        "crs": "EPSG:4326",
        "bbox": [min(xs), min(ys), max(xs), max(ys)],
        "uncompressed_bytes": len(raw),
        "compressed_bytes": len(compressed),
        "sha256": hashlib.sha256(compressed).hexdigest(),
    }


def create_opengis_fixture() -> None:
    reset_generated_directory(OPENGIS)
    hidden = OPENGIS / ".opengis"
    run = hidden / "runs" / "run-phase0-001"
    context = hidden / "contexts"
    workflow = hidden / "workflows"
    operation = hidden / "operations" / "legacy-sample"
    memory = hidden / "memory"
    workflow_steps = hidden / "workflow_steps"
    operation_run = hidden / "operation-runs" / "legacy-sample" / "operation-run-phase0-001"
    skill = hidden / "skills" / "synthetic-skill"
    for path in (run, context, workflow, operation, memory, workflow_steps, operation_run, skill):
        path.mkdir(parents=True, exist_ok=True)

    write_json(hidden / "sessions.json", {
        "sessions": {
            "session-phase0-001": {
                "id": "session-phase0-001",
                "kind": "chat",
                "profile_name": "gis-build",
                "parent_id": None,
                "run_id": "run-phase0-001",
                "title": "Synthetic migration fixture",
                "status": "success",
                "created_at": 1767225600.0,
                "updated_at": 1767225601.0,
                "children": [],
                "summary": "Fixture ready",
                "metadata": {"conversation_id": "conversation-phase0-001", "workspace_path": "${WORKSPACE}"},
            }
        },
        "inbox": {
            "inbox-phase0-001": {
                "id": "inbox-phase0-001",
                "prompt": "Synthetic migration fixture",
                "conversation_id": "conversation-phase0-001",
                "profile_name": "gis-build",
                "session_id": "session-phase0-001",
                "run_id": "run-phase0-001",
                "status": "success",
                "error": "",
                "created_at": 1767225600.0,
                "updated_at": 1767225601.0,
                "metadata": {"queue_id": "queue-phase0-001"},
            }
        },
    })
    write_json(hidden / "agents.json", {"profiles": [{"name": "gis-build", "mode": "build", "description": "Synthetic profile", "max_steps": 20, "tool_groups": ["system", "map"], "permission_level": "safe_write", "metadata": {"permission_enforce": False}}]})
    write_json(hidden / "permissions.json", {"rules": [{"id": "rule-phase0-001", "tool": "delete_file", "action": "ask", "profile": "*", "reason": "Synthetic migration rule", "created_at": "2026-01-01T00:00:00Z"}]})
    write_json(hidden / "titled_conversations.json", ["conversation-phase0-001"])
    write_json(hidden / "skill-sources.json", {"paths": ["${WORKSPACE}/shared-skills"]})
    (hidden / "memory.md").write_text("# Synthetic project memory\n", encoding="utf-8")
    memory_rows = {
        "facts.jsonl": {"id": "memory-fact-001", "kind": "fact", "scope": "project", "title": "Fixture CRS", "content": "Synthetic inputs use EPSG:4326.", "tags": ["fixture"], "confidence": 1.0, "created_at": 1767225600.0, "last_used_at": 0.0, "metadata": {}},
        "recipes.jsonl": {"id": "memory-recipe-001", "kind": "recipe", "scope": "project", "title": "Fixture recipe", "content": "Read then summarize the fixture.", "tags": ["fixture"], "confidence": 1.0, "created_at": 1767225600.0, "last_used_at": 0.0, "metadata": {}},
        "datasets.jsonl": {"id": "memory-dataset-001", "kind": "dataset", "scope": "project", "title": "Fixture dataset", "content": "points_wgs84.geojson", "tags": ["fixture"], "confidence": 1.0, "created_at": 1767225600.0, "last_used_at": 0.0, "metadata": {}},
        "failures.jsonl": {"id": "memory-failure-001", "kind": "failure", "scope": "project", "title": "Fixture failure", "content": "Synthetic recoverable failure.", "tags": ["fixture"], "confidence": 1.0, "created_at": 1767225600.0, "last_used_at": 0.0, "metadata": {}},
    }
    for filename, row in memory_rows.items():
        write_jsonl(memory / filename, [row])
    write_json(context / "conversation-phase0-001.json", {"schema_version": "1.0", "conversation_id": "conversation-phase0-001", "messages": [{"role": "user", "content": "Synthetic migration fixture"}, {"role": "assistant", "content": "Fixture ready"}], "working_state": {"workspace": "${WORKSPACE}"}})
    write_json(workflow / "workflow-phase0.flow.json", {"schema_version": "1.0", "id": "workflow-phase0", "name": "Synthetic workflow", "nodes": [{"id": "node-a", "task": "Read fixture", "output": "summary"}, {"id": "node-b", "task": "Report fixture", "input": "summary"}], "edges": [{"source": "node-a", "target": "node-b"}]})
    (workflow_steps / "step1_node-a.md").write_text("# Step 1: Read fixture\n\n## Full Output\n\nSynthetic step output.\n", encoding="utf-8")
    write_json(hidden / "artifacts.jsonl.meta.json", {"note": "The adjacent JSONL is the compatibility target."})
    write_jsonl(hidden / "artifacts.jsonl", [{"id": "artifact-phase0-001", "run_id": "run-phase0-001", "type": "geojson", "path": "${WORKSPACE}/output/result.geojson", "title": "Synthetic result", "created_at": "2026-01-01T00:00:01Z"}])
    write_json(run / "meta.json", {"run_id": "run-phase0-001", "status": "completed", "created_at": "2026-01-01T00:00:00Z", "completed_at": "2026-01-01T00:00:01Z", "workspace_path": "${WORKSPACE}", "conversation_id": "conversation-phase0-001", "pre_sha": "0000000000000000000000000000000000000000", "post_sha": "1111111111111111111111111111111111111111"})
    write_jsonl(run / "steps.jsonl", [{"step": 1, "status": "completed", "started_at": "2026-01-01T00:00:00Z", "completed_at": "2026-01-01T00:00:01Z"}])
    write_jsonl(run / "tool_calls.jsonl", [{"id": "call-phase0-001", "tool": "read_file", "arguments": {"file_path": "${WORKSPACE}/input.geojson"}, "status": "completed", "result": {"output": "synthetic"}}])
    write_jsonl(run / "artifacts.jsonl", [{"id": "artifact-phase0-001", "path": "${WORKSPACE}/output/result.geojson", "type": "geojson"}])
    write_jsonl(run / "events.jsonl", [{"type": "run_started", "data": {"run_id": "run-phase0-001"}}, {"type": "run_completed", "data": {"run_id": "run-phase0-001"}}])
    write_jsonl(run / "message_parts.jsonl", [{"id": "part-phase0-001", "type": "text", "status": "done", "content": "Synthetic result"}])
    write_jsonl(run / "llm_usage.jsonl", [{"provider": "synthetic", "model": "fixture", "prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}])
    write_json(operation / "operation.json", {"schema_version": "1.0", "id": "legacy-sample", "name": "Synthetic legacy operation", "entry": "main.py", "runtime": {"language": "python", "python": ">=3.12", "dependencies": []}, "input_schema": {"type": "object"}, "output_schema": {"type": "object"}})
    (operation / "main.py").write_text("def main():\n    return {'success': True}\n", encoding="utf-8")
    (operation / "README.md").write_text("# Synthetic legacy Operation\n", encoding="utf-8")
    write_json(operation_run / "input.json", {"workspace": "${WORKSPACE}", "operation_id": "legacy-sample", "params": {}})
    write_json(operation_run / "output.json", {"success": True, "summary": "Synthetic operation output"})
    (operation_run / "stdout.log").write_text("synthetic operation completed\n", encoding="utf-8")
    (operation_run / "stderr.log").write_text("", encoding="utf-8")
    write_json(operation_run / "run.json", {"run_id": "operation-run-phase0-001", "operation_id": "legacy-sample", "status": "success", "returncode": 0, "started_at": "2026-01-01T00:00:00Z", "finished_at": "2026-01-01T00:00:01Z", "scope": "builtin", "input_path": ".opengis/operation-runs/legacy-sample/operation-run-phase0-001/input.json", "output_path": ".opengis/operation-runs/legacy-sample/operation-run-phase0-001/output.json", "stdout_path": ".opengis/operation-runs/legacy-sample/operation-run-phase0-001/stdout.log", "stderr_path": ".opengis/operation-runs/legacy-sample/operation-run-phase0-001/stderr.log", "output": {"success": True, "summary": "Synthetic operation output"}})
    write_json(hidden / "operation-runs" / "legacy-sample" / "latest.json", {"operation_id": "legacy-sample", "scope": "builtin", "last_run": "operation-run-phase0-001", "last_success_run": "operation-run-phase0-001", "updated_at": "2026-01-01T00:00:01Z"})
    (skill / "SKILL.md").write_text("---\nname: synthetic-skill\ndescription: Anonymous Phase 0 fixture.\n---\n\n# Synthetic skill\n", encoding="utf-8")

    schema_inventory = {
        "schema_version": "1.0",
        "scope": "workspace-local persistent .opengis files observed in current Python source; caches are listed but excluded from round-trip gates",
        "stores": [
            {"name": "sessions-and-inbox", "path_pattern": ".opengis/sessions.json", "fixture": ".opengis/sessions.json", "owner": "SessionStore"},
            {"name": "agent-profiles", "path_pattern": ".opengis/agents.json", "fixture": ".opengis/agents.json", "owner": "AgentProfileStore"},
            {"name": "permissions", "path_pattern": ".opengis/permissions.json", "fixture": ".opengis/permissions.json", "owner": "PermissionRuleStore"},
            {"name": "conversation-context", "path_pattern": ".opengis/contexts/<conversation_id>.json", "fixture": ".opengis/contexts/conversation-phase0-001.json", "owner": "ContextPersistence"},
            {"name": "titled-conversations", "path_pattern": ".opengis/titled_conversations.json", "fixture": ".opengis/titled_conversations.json", "owner": "RpcHandler"},
            {"name": "artifact-index", "path_pattern": ".opengis/artifacts.jsonl", "fixture": ".opengis/artifacts.jsonl", "owner": "ArtifactIndex"},
            {"name": "structured-memory", "path_pattern": ".opengis/memory/{facts,recipes,datasets,failures}.jsonl", "fixture": ".opengis/memory/*.jsonl", "owner": "MemoryStore"},
            {"name": "legacy-memory", "path_pattern": ".opengis/memory.md", "fixture": ".opengis/memory.md", "owner": "ProjectMemory"},
            {"name": "workflows", "path_pattern": ".opengis/workflows/*.flow.json", "fixture": ".opengis/workflows/workflow-phase0.flow.json", "owner": "WorkflowDocumentStore"},
            {"name": "workflow-step-output", "path_pattern": ".opengis/workflow_steps/step<index>_<node_id>.md", "fixture": ".opengis/workflow_steps/step1_node-a.md", "owner": "WorkflowOutputStore"},
            {"name": "run-archive", "path_pattern": ".opengis/runs/<run_id>/{meta.json,*.jsonl}", "fixture": ".opengis/runs/run-phase0-001", "owner": "RunArchive"},
            {"name": "workspace-operations", "path_pattern": ".opengis/operations/<operation_id>/", "fixture": ".opengis/operations/legacy-sample", "owner": "OperationStore"},
            {"name": "operation-runs", "path_pattern": ".opengis/operation-runs/<builtin_operation_id>/<run_id>/", "fixture": ".opengis/operation-runs/legacy-sample/operation-run-phase0-001", "owner": "OperationStore"},
            {"name": "skill-sources", "path_pattern": ".opengis/skill-sources.json", "fixture": ".opengis/skill-sources.json", "owner": "SkillDiscovery"},
            {"name": "workspace-skills", "path_pattern": ".opengis/skills/<skill>/SKILL.md", "fixture": ".opengis/skills/synthetic-skill/SKILL.md", "owner": "SkillDiscovery"},
            {"name": "raster-cache", "path_pattern": ".opengis/raster-cache/", "fixture": None, "owner": "RasterService", "classification": "regenerable-cache"},
        ],
    }
    write_json(hidden / "schema-inventory.json", schema_inventory)


def load_operation_module(name: str):
    path = REPO / "python-backend" / "opengis_backend" / "operations" / "builtin" / name / "main.py"
    spec = importlib.util.spec_from_file_location(f"phase0_{name}", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def operation_golden() -> dict[str, Any]:
    output = ROOT / "operation-output"
    reset_generated_directory(output)
    output.mkdir(parents=True)
    source = GIS / "points_wgs84.geojson"

    converter = load_operation_module("format_converter")
    converted = converter.convert(str(source), str(output / "points.csv"), output_format="csv", include_geometry_csv=True)

    clustering = load_operation_module("advanced_clustering")
    gdf = clustering.load_data(str(source))
    coords = np.column_stack([gdf.geometry.x, gdf.geometry.y])
    labels = clustering.perform_clustering(coords, method="kmeans", n_clusters=2, random_state=42)

    kde = load_operation_module("kernel_density")
    kde_result = kde.run_kernel_density(str(REPO), {"input_path": str(source), "output_dir": str(output / "kde"), "bandwidth_meters": 800000, "cell_size_meters": 200000, "kernel": "gaussian", "output_contours": False, "output_polygons": False, "max_grid_cells": 10000})

    return {
        "format_converter": {key: value for key, value in converted.items() if key not in {"elapsed_seconds", "output_path", "input_path"}},
        "advanced_clustering_kmeans": {"input_features": len(gdf), "cluster_count": len(set(int(value) for value in labels)), "labels": [int(value) for value in labels], "random_state": 42},
        "kernel_density": {key: value for key, value in kde_result.items() if key not in {"raster_path", "polygons_path", "contours_path", "output_files", "traceback"}},
    }


def main() -> None:
    create_gis_fixtures()
    create_opengis_fixture()
    summaries = {
        "schema_version": "1.0",
        "vectors": [
            vector_summary(GIS / "points_wgs84.geojson"),
            vector_summary(GIS / "lines_wgs84.geojson"),
            vector_summary(GIS / "polygons_wgs84.geojson"),
            vector_summary(GIS / "edge_cases_wgs84.geojson"),
            vector_summary(GIS / "points_shapefile" / "points.shp"),
            vector_summary(GIS / "points.gpkg"),
        ],
        "raster": raster_summary(GIS / "small_raster_3857.tif"),
        "scale_vectors": [
            scale_summary(GIS / "scale" / "points_10000.geojson.gz"),
            scale_summary(GIS / "scale" / "points_100000.geojson.gz"),
        ],
        "operations": operation_golden(),
        "tolerances": {
            "bbox_absolute": 1e-6,
            "area_length_relative": 1e-6,
            "raster_stat_relative": 1e-5,
            "cluster_labels": "labels may be permuted; compare partition membership and counts",
        },
    }
    write_json(GOLDEN / "gis-and-operations-summary.json", summaries)
    print(json.dumps({"gis_fixture_dir": str(GIS), "opengis_fixture_dir": str(OPENGIS), "golden": str(GOLDEN / 'gis-and-operations-summary.json')}, ensure_ascii=False))


if __name__ == "__main__":
    main()
