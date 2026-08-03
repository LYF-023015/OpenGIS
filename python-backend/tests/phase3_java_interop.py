"""Validate a Java-written workspace with the current Python persistence readers."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from opengis_backend.agent.governance.permission_store import PermissionRuleStore
from opengis_backend.agent.governance.profile import AgentProfileStore
from opengis_backend.agent.session.session import SessionStore
from opengis_backend.agent.workflow.workflow_store import WorkflowDocumentStore
from opengis_backend.runs.archive import RunArchive
from opengis_backend.workspace.memory_store import MemoryStore


def verify(workspace: Path) -> None:
    session_store = SessionStore(str(workspace))
    assert session_store.list_recent()[0]["id"] == "java-session"
    assert session_store.list_inbox()[0]["id"] == "java-inbox"

    profiles = AgentProfileStore(str(workspace)).load_all()
    assert profiles["java-profile"].mode.value == "build"
    assert PermissionRuleStore(str(workspace)).list_rules()[0]["id"] == "java-rule"

    workflow = WorkflowDocumentStore(str(workspace)).load(workflow_id="java-workflow")
    assert workflow is not None
    assert workflow.name == "Java workflow"

    memories = MemoryStore(str(workspace)).list(limit=20)
    assert any(memory.id == "java-memory" for memory in memories)

    run = RunArchive.load(str(workspace), "java-run")
    assert run is not None
    assert run.meta["status"] == "error"
    assert run.read_tool_calls()[0]["status"] == "error"
    assert run.read_message_parts()[-1]["status"] == "failed"

    opengis = workspace / ".opengis"
    assert json.loads((opengis / "titled_conversations.json").read_text(encoding="utf-8")) == [
        "java-conversation"
    ]
    assert json.loads((opengis / "artifacts.jsonl").read_text(encoding="utf-8"))["id"] == "java-artifact"
    assert json.loads((workspace / "worker" / "java-worker" / "metadata.json").read_text(encoding="utf-8"))["id"] == "java-worker"
    assert list((workspace / "script").glob("*.metadata.json"))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: phase3_java_interop.py <workspace>")
    verify(Path(sys.argv[1]).resolve())
    print("PHASE3_JAVA_TO_PYTHON=ok")
