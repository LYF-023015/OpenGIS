"""Phase 2 guard: Python protocol values must match the canonical shared schema."""

from __future__ import annotations

import json
from pathlib import Path

from opengis_backend.runtime.protocol_types import (
    GeometryType,
    LayerSource,
    LayerStyleType,
    METHOD_PREFIX_CHAT,
    METHOD_PREFIX_EVENT,
    METHOD_PREFIX_RPC,
    PROTOCOL_VERSION,
)


SCHEMA_PATH = (
    Path(__file__).resolve().parents[2]
    / "java-backend"
    / "opengis-common"
    / "src"
    / "main"
    / "resources"
    / "opengis"
    / "protocol"
    / "opengis-protocol-3.0.schema.json"
)


def test_python_protocol_matches_the_canonical_shared_schema() -> None:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    definitions = schema["$defs"]

    assert definitions["protocolVersion"]["const"] == PROTOCOL_VERSION
    assert definitions["jsonRpcVersion"]["const"] == "2.0"
    assert definitions["geometryType"]["enum"] == [item.value for item in GeometryType]
    assert definitions["layerSource"]["enum"] == [item.value for item in LayerSource]
    assert definitions["layerStyleType"]["enum"] == [item.value for item in LayerStyleType]
    assert (METHOD_PREFIX_RPC, METHOD_PREFIX_CHAT, METHOD_PREFIX_EVENT) == (
        "rpc.",
        "chat.",
        "event.",
    )
