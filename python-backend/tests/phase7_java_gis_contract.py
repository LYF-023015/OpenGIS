"""Generate the approved Python/Java Phase 7 GIS difference report.

This script is invoked only through python-backend/.venv by the Java IT.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from opengis_backend.integrations.gis.loader import GISLoader


def main() -> int:
    payload_path = Path(sys.argv[1])
    report_path = Path(sys.argv[2])
    payload = json.loads(payload_path.read_text(encoding="utf-8"))
    source = Path(payload["source"])
    java = payload["java"]
    python = GISLoader().read_metadata(str(source))

    tolerances = {
        "feature_count": 0,
        "bounds_absolute_degrees": 1e-9,
        "file_size_bytes": 0,
    }
    checks = {
        "format_name": python.format_name == java["formatName"],
        "feature_count": abs(int(python.feature_count) - int(java["featureCount"]))
        <= tolerances["feature_count"],
        "bounds": all(
            abs(float(left) - float(right)) <= tolerances["bounds_absolute_degrees"]
            for left, right in zip(python.bounds, java["bounds"], strict=True)
        ),
        "file_size": source.stat().st_size == int(java["fileSizeBytes"]),
    }
    report = {
        "status": "approved" if all(checks.values()) else "rejected",
        "source": str(source),
        "python": {
            "formatName": python.format_name,
            "featureCount": python.feature_count,
            "bounds": list(python.bounds),
            "fileSizeBytes": source.stat().st_size,
        },
        "java": java,
        "tolerances": tolerances,
        "checks": checks,
    }
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"status": report["status"], "checks": checks}))
    return 0 if report["status"] == "approved" else 1


if __name__ == "__main__":
    raise SystemExit(main())
