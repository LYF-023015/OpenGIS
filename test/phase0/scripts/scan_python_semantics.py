"""Inventory Python-specific consumers outside python-backend for Java migration."""

from __future__ import annotations

import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "test" / "phase0" / "baseline" / "python-semantic-consumers.json"
TEXT_SUFFIXES = {".ts", ".tsx", ".js", ".mjs", ".json", ".yml", ".yaml", ".md", ".html"}
PATTERNS = {
    "python_client": re.compile(r"pythonClient|PythonClient", re.IGNORECASE),
    "python_runtime": re.compile(r"python-backend|opengis_backend|pyproject|venv|setup:python", re.IGNORECASE),
    "python_code_ui": re.compile(r"Python code|Python 脚本|python script|rpc\.code|\.py\b", re.IGNORECASE),
    "pip_dependency": re.compile(r"\bpip\b|requirements\.txt", re.IGNORECASE),
}


def decision(path: str, terms: set[str]) -> tuple[str, str, str]:
    if path.startswith("electron/") or path in {"package.json", "electron-builder.yml", "electron.vite.config.ts"}:
        return ("electron-launcher", "opengis-server/opengis-ui", "replace with BackendManager + bundled JRE launcher; keep compatibility flag during migration")
    if "script-runner" in path or "CodeStep" in path or "rpc.code" in terms:
        return ("code-execution-ui", "opengis-platform/opengis-ui", "retain protocol initially; change language metadata and labels from Python to Java")
    if path.startswith("src/services/pythonClient") or "python_client" in terms:
        return ("transport-client", "opengis-ui", "rename to backendClient only after Java protocol parity; behavior remains JSON-RPC")
    if path.startswith("src/"):
        return ("renderer-consumer", "opengis-ui", "preserve UI behavior and replace Python-specific labels/types with backend-neutral terms")
    if path.startswith("docs/") or path.startswith("README"):
        return ("documentation", "docs", "update process diagrams, commands and extension examples at the owning migration phase")
    return ("build-config", "opengis-server/opengis-ui", "replace Python packaging/runtime entry with Java module or bundled JRE equivalent")


def main() -> None:
    candidates: list[Path] = []
    for root_name in ("src", "electron", "docs"):
        root = REPO / root_name
        for path in root.rglob("*"):
            if path.is_file() and path.suffix.lower() in TEXT_SUFFIXES:
                relative = path.relative_to(REPO).as_posix()
                if (
                    relative.startswith("docs/migration/phase0/")
                    or relative == "docs/migration/migration-matrix.yaml"
                    or relative.startswith("docs/adr/")
                ):
                    continue
                candidates.append(path)
    for name in ("package.json", "electron-builder.yml", "electron.vite.config.ts", "README.md", "README.zh.md"):
        candidates.append(REPO / name)

    entries = []
    for path in sorted(set(candidates)):
        text = path.read_text(encoding="utf-8", errors="replace")
        matched = {name for name, pattern in PATTERNS.items() if pattern.search(text)}
        if not matched:
            continue
        relative = path.relative_to(REPO).as_posix()
        snippets = []
        for line_number, line in enumerate(text.splitlines(), start=1):
            if any(pattern.search(line) for pattern in PATTERNS.values()):
                snippets.append({"line": line_number, "text": line.strip()[:240]})
            if len(snippets) == 8:
                break
        category, target, replacement = decision(relative, matched)
        entries.append({
            "file": relative,
            "category": category,
            "matched_semantics": sorted(matched),
            "reference_count": sum(len(pattern.findall(text)) for pattern in PATTERNS.values()),
            "sample_references": snippets,
            "java_target": target,
            "replacement": replacement,
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"schema_version": "1.0", "consumer_count": len(entries), "consumers": entries}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"consumer_count": len(entries), "output": str(OUT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
