"""Read-only Phase 8 migration contract checks.

Run this file only with python-backend/.venv.  It verifies that the Java side keeps
the legacy Python asset readers and public lifecycle names while never importing
or executing Python from the Java runtime.
"""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-backend"
PYTHON = ROOT / "python-backend" / "opengis_backend"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_operation_v2_keeps_required_lifecycle_and_legacy_reader() -> None:
    service = text(
        JAVA
        / "opengis-gis/src/main/java/org/opengis/gis/operation/OperationService.java"
    )
    builtin_root = (
        JAVA
        / "opengis-gis/src/main/java/org/opengis/gis/operation/builtin"
    )
    builtins = "\n".join(text(path) for path in builtin_root.glob("*Operation.java"))
    for method in (
        " list(",
        " get(",
        " copyToWorkspace(",
        " create(",
        " edit(",
        " validate(",
        " run(",
        " promoteScript(",
    ):
        assert method in service
    assert "legacy-python" in service
    assert "Python v1 operations are never executed" in service
    assert "format_converter" in builtins
    assert "advanced_clustering" in builtins
    assert "kernel_density" in builtins
    assert (PYTHON / "operations/store.py").is_file()


def test_script_sdk_and_child_protocol_cover_bidirectional_clients() -> None:
    sdk = JAVA / "opengis-script-sdk/src/main/java/org/opengis/script/sdk"
    for api in (
        "OpenGisScript.java",
        "ToolClient.java",
        "ArtifactClient.java",
        "MapClient.java",
        "ProgressEmitter.java",
        "ProtocolTransport.java",
    ):
        assert (sdk / api).is_file()
    runner = text(
        JAVA / "opengis-code/src/main/java/org/opengis/code/runner/JavaScriptRunner.java"
    )
    assert "destroyTree" in runner
    assert "MAX_PROTOCOL_LINE" in runner
    assert "dependencyChecksums" in text(
        JAVA / "opengis-code/src/main/java/org/opengis/code/runner/ScriptRunResult.java"
    )


def test_java_worker_keeps_public_lifecycle_and_generates_python_migration_report() -> None:
    manager = text(
        JAVA / "opengis-worker/src/main/java/org/opengis/worker/WorkerManager.java"
    )
    migration = text(
        JAVA
        / "opengis-worker/src/main/java/org/opengis/worker/WorkerMigrationService.java"
    )
    for method in (
        "createAndStart(",
        "start(",
        "pause(",
        "restart(",
        "delete(",
        "get(",
        "list(",
        "waitForUpdate(",
        "restore(",
    ):
        assert method in manager
    assert "DEFAULT_MAX_RUNNING = 2" in manager
    assert "MAX_RESTARTS = 3" in manager
    assert "java_template" in migration
    assert "manual_migration_required" in migration
    assert (PYTHON / "worker/manager.py").is_file()
