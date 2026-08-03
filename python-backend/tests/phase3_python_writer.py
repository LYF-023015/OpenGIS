"""Create a temporary workspace through current Python writers for Java read tests."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from opengis_backend.agent.governance.permission import PermissionAction
from opengis_backend.agent.governance.permission_store import PermissionRuleStore
from opengis_backend.agent.governance.profile import AgentProfileStore
from opengis_backend.agent.session.session import (
    AgentInboxItem,
    AgentSession,
    InboxStatus,
    SessionKind,
    SessionStatus,
    SessionStore,
)
from opengis_backend.agent.telemetry.script_archive import ScriptArchive
from opengis_backend.agent.workflow.workflow_model import WorkflowDocument, WorkflowNode
from opengis_backend.agent.workflow.workflow_store import WorkflowDocumentStore
from opengis_backend.runs.archive import RunArchive
from opengis_backend.workspace.memory_store import MemoryRecord, MemoryStore


def write(workspace: Path) -> None:
    workspace.mkdir(parents=True, exist_ok=True)
    store = SessionStore(str(workspace))
    session = AgentSession.create(
        kind=SessionKind.CHAT,
        profile_name="gis-build",
        run_id="python-run",
        title="Python writer",
    )
    session.finish(status=SessionStatus.SUCCESS, summary="ready")
    store.upsert(session)
    inbox = AgentInboxItem.create(
        prompt="Python writer",
        conversation_id="python-conversation",
        profile_name="gis-build",
    )
    inbox.session_id = session.id
    inbox.run_id = "python-run"
    inbox.status = InboxStatus.SUCCESS
    store.add_inbox(inbox)

    AgentProfileStore(str(workspace)).install_defaults()
    PermissionRuleStore(str(workspace)).add_rule(
        tool="read_file",
        action=PermissionAction.ALLOW,
        reason="phase3 interop",
    )
    MemoryStore(str(workspace)).add(
        MemoryRecord.create(
            kind="fact",
            scope="project",
            title="Python memory",
            content="Python writer can be read by Java.",
        )
    )
    WorkflowDocumentStore(str(workspace)).save(
        WorkflowDocument(
            name="Python workflow",
            description="phase3",
            nodes=[WorkflowNode(id="node-a", title="Read fixture")],
            edges=[],
        ),
        workflow_id="python-workflow",
    )
    scripts = ScriptArchive.for_run(workspace_path=str(workspace), run_id="python-run")
    scripts.write_step(1, "print('python')", semantic_name="python-writer")

    run = RunArchive.open(
        run_id="python-run",
        prompt="Python writer",
        workspace_path=str(workspace),
        model="test-model",
    )
    run.record_step(step=1, code="print('python')", output="python")
    run.close(status="success", final_answer="done")

    opengis = workspace / ".opengis"
    (opengis / "artifacts.jsonl").write_text(
        json.dumps({"id": "python-artifact", "run_id": "python-run", "type": "text"}) + "\n",
        encoding="utf-8",
    )
    (opengis / "titled_conversations.json").write_text(
        json.dumps(["python-conversation"], indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: phase3_python_writer.py <workspace>")
    write(Path(sys.argv[1]).resolve())
    print("PHASE3_PYTHON_WRITER=ok")
