"""Cross-language gate for Java Workflow schema v2 and migration reports.

Invoked only through python-backend/.venv by the Java Phase 6 integration test.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from opengis_backend.agent.workflow.workflow_model import WorkflowDocument


def main() -> int:
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    workflow = payload["workflow"]
    assert workflow["schemaVersion"] == 2
    assert all(node["type"] == node["execution"]["kind"] for node in workflow["nodes"])
    assert all(not node["execution"]["ref"].lower().endswith(".py") for node in workflow["nodes"])
    # The Python reader remains able to inspect v2 during coexistence, but Java is the executor.
    inspected = WorkflowDocument.from_json(workflow)
    assert [node.id for node in inspected.nodes] == ["a", "b"]
    report = payload["migration_report"]
    assert report["status"] == "manual_required"
    assert {issue["code"] for issue in report["issues"]} >= {"python_script_reference", "python_hook"}
    print(json.dumps({"status": "ok", "schemaVersion": 2, "nodes": len(inspected.nodes)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
