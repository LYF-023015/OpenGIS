"""Replay deterministic Renderer-to-backend Phase 0 transcripts in-process."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from opengis_backend.server import WS_TOKEN, app


REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / "test" / "phase0" / "rpc"


def substitute(value: Any, workspace: str) -> Any:
    if isinstance(value, str):
        return value.replace("${WORKSPACE}", workspace)
    if isinstance(value, list):
        return [substitute(item, workspace) for item in value]
    if isinstance(value, dict):
        return {key: substitute(item, workspace) for key, item in value.items()}
    return value


def assert_match(actual: Any, expected: Any, path: str = "$") -> None:
    if isinstance(expected, dict) and set(expected) == {"$type"}:
        expected_type = expected["$type"]
        types = {"array": list, "object": dict, "string": str, "number": (int, float), "boolean": bool, "null": type(None)}
        assert isinstance(actual, types[expected_type]), f"{path}: expected {expected_type}, got {type(actual).__name__}"
        return
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path}: expected object, got {type(actual).__name__}"
        for key, value in expected.items():
            assert key in actual, f"{path}: missing key {key}; actual={actual}"
            assert_match(actual[key], value, f"{path}.{key}")
        return
    if isinstance(expected, list):
        assert isinstance(actual, list), f"{path}: expected array"
        assert len(actual) == len(expected), f"{path}: expected {len(expected)} items, got {len(actual)}"
        for index, value in enumerate(expected):
            assert_match(actual[index], value, f"{path}[{index}]")
        return
    assert actual == expected, f"{path}: expected {expected!r}, got {actual!r}"


def main() -> None:
    rows = [json.loads(line) for line in (ROOT / "protocol-transcripts.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    envelope_schema = json.loads((ROOT / "json-rpc-envelope.schema.json").read_text(encoding="utf-8"))
    envelope_validator = Draft202012Validator(envelope_schema)
    replayed = 0
    structural = 0

    with tempfile.TemporaryDirectory(prefix="opengis-phase0-rpc-") as workspace, TestClient(app) as client:
        for row in rows:
            if row.get("direction") == "backend-to-ui":
                messages = row.get("messages") or [row.get("message")]
                for message in messages:
                    errors = list(envelope_validator.iter_errors(message))
                    assert not errors, f"{row['name']}: {errors}"
                    structural += 1
                continue

            if row["name"] == "health":
                response = client.get(row["request"]["path"])
                assert response.status_code == row["expect"]["status"]
                assert_match(response.json(), row["expect"]["json"], row["name"])
                replayed += 1
                continue

            if row["name"] == "invalid-ws-token":
                with client.websocket_connect("/ws?token=invalid") as websocket:
                    actual = websocket.receive_json()
                assert_match(actual, {"error": row["expect"]["error"]}, row["name"])
                replayed += 1
                continue

            with client.websocket_connect(f"/ws?token={WS_TOKEN}") as websocket:
                if "request_raw" in row:
                    websocket.send_text(row["request_raw"])
                else:
                    websocket.send_json(substitute(row["request"], workspace))
                actual = websocket.receive_json()
            assert_match(actual, substitute(row["expect"], workspace), row["name"])
            replayed += 1

    print(json.dumps({"status": "ok", "transcripts": len(rows), "replayed": replayed, "structural_messages": structural}, ensure_ascii=False))


if __name__ == "__main__":
    main()
