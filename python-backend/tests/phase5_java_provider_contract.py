"""Cross-language gate for every frontend-visible LLM provider preset.

Invoked only with python-backend/.venv by the Java Phase 5 integration test.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> int:
    java_catalog = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    repository = Path(__file__).resolve().parents[2]
    baseline = json.loads(
        (repository / "test/phase0/providers/provider-migration.json").read_text(
            encoding="utf-8"
        )
    )

    expected = {provider["id"]: provider for provider in baseline["providers"]}
    actual = {provider["id"]: provider for provider in java_catalog["providers"]}
    if set(actual) != set(expected):
        raise AssertionError(
            f"provider IDs differ: missing={sorted(set(expected) - set(actual))}, "
            f"extra={sorted(set(actual) - set(expected))}"
        )

    for provider_id, expected_provider in expected.items():
        java_provider = actual[provider_id]
        assert java_provider["protocol"] == expected_provider["current_protocol"]
        assert java_provider["base_url"] == expected_provider["base_url"]
        assert java_provider["default_model"] == expected_provider["default_model"]
        assert java_provider["decision"] == "migrate"
        expected_adapter = expected_provider["java_adapter"]
        assert java_provider["adapter"] == expected_adapter

    print(json.dumps({"status": "ok", "providers": len(actual)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
