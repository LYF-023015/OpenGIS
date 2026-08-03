"""Run deterministic local Phase 0 microbenchmarks without external LLM/network calls."""

from __future__ import annotations

import json
import statistics
import tempfile
import time
from pathlib import Path
from typing import Callable

import geopandas as gpd

from opengis_backend.agent.llm import LLMResponse
from opengis_backend.agent.loop.agent_loop import AgentLoop
from opengis_backend.agent.loop.types import CodeExecResult
from opengis_backend.integrations.gis.raster_service import register_raster, render_registered_raster_tile
from opengis_backend.tools.builtin.read_file_tool import _read_lines_sync
from opengis_backend.tools.context import ToolContext


REPO = Path(__file__).resolve().parents[3]
GIS = REPO / "test" / "phase0" / "fixtures" / "gis"


def timings(action: Callable[[], object], repeats: int) -> dict[str, float]:
    values: list[float] = []
    for _ in range(repeats):
        start = time.perf_counter()
        action()
        values.append((time.perf_counter() - start) * 1000.0)
    ordered = sorted(values)
    p95_index = min(len(ordered) - 1, max(0, int(len(ordered) * 0.95) - 1))
    return {
        "repeats": repeats,
        "min_ms": round(min(values), 3),
        "median_ms": round(statistics.median(values), 3),
        "p95_ms": round(ordered[p95_index], 3),
        "max_ms": round(max(values), 3),
    }


def mock_agent_turn() -> str:
    loop = AgentLoop(
        llm_call=lambda *args, **kwargs: LLMResponse(content="Phase 0 mock completion", finish_reason="stop"),
        executor_call=lambda code: CodeExecResult(logs="", output=None, error=None),
        system_prompt="Phase 0 deterministic mock",
    )
    return loop.run("Return the deterministic baseline response.")


def create_point_geojson(path: Path, count: int) -> None:
    features = [
        {"type": "Feature", "id": index, "properties": {"value": index}, "geometry": {"type": "Point", "coordinates": [100.0 + (index % 1000) * 0.001, 20.0 + (index // 1000) * 0.001]}}
        for index in range(count)
    ]
    path.write_text(json.dumps({"type": "FeatureCollection", "features": features}, separators=(",", ":")), encoding="utf-8")


def main() -> None:
    points = GIS / "points_wgs84.geojson"
    raster = GIS / "small_raster_3857.tif"
    ctx = ToolContext(meta={"workspace_path": str(REPO)})
    raster_registration = register_raster(
        str(raster),
        style={
            "stops": [
                {"value": 0.0, "color": "#000000"},
                {"value": 1.0, "color": "#ffffff"},
            ],
            "stopsUnit": "normalized",
        },
    )

    results: dict[str, object] = {
        "schema_version": "1.0",
        "scope": "local deterministic baseline; no external LLM or network",
        "ordinary_read_file": timings(lambda: _read_lines_sync(str(points), 1, 2000, ctx=ctx), 50),
        "mock_first_agent_turn": timings(mock_agent_turn, 30),
        "raster_tile_z0": timings(lambda: render_registered_raster_tile(raster_registration.raster_id, 0, 0, 0), 10),
    }
    with tempfile.TemporaryDirectory(prefix="opengis-phase0-benchmark-") as temp:
        root = Path(temp)
        for count in (10_000, 100_000):
            path = root / f"points-{count}.geojson"
            create_start = time.perf_counter()
            create_point_geojson(path, count)
            create_ms = (time.perf_counter() - create_start) * 1000.0
            read_start = time.perf_counter()
            dataset = gpd.read_file(path)
            read_ms = (time.perf_counter() - read_start) * 1000.0
            results[f"vector_{count}"] = {
                "file_bytes": path.stat().st_size,
                "generate_ms": round(create_ms, 3),
                "geopandas_read_ms": round(read_ms, 3),
                "feature_count": len(dataset),
                "bbox": [round(float(value), 6) for value in dataset.total_bounds],
            }
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
