# OpenGIS Phase 0 compatibility assets

This directory is the executable compatibility baseline for the Python-to-Java migration.

## Contents

- `baseline/`: captured environment, test results and performance values.
- `rpc/`: generated method inventory, JSON-RPC envelope schema and 20 representative transcripts.
- `providers/`: 24 Provider decisions plus OpenAI-compatible and Anthropic-compatible offline protocol fixtures.
- `fixtures/gis/`: deterministic GeoJSON, CSV, Shapefile, GeoPackage and GeoTIFF inputs.
- `fixtures/opengis-workspace/`: fully synthetic, anonymous `.opengis` workspace.
- `fixtures/operation-output/`: deterministic outputs used while creating the Operation golden result.
- `golden/python/`: Python vector, raster and Operation summaries with approved comparison tolerances.
- `scripts/`: reproducible exporters, generators, validators, probes and benchmarks.

## Reproduce

Run from the repository root. Create the isolated environment before any Python command:

```powershell
py -3.13 -m venv python-backend/.venv
$python = 'python-backend/.venv/Scripts/python.exe'
& $python -m pip install --upgrade pip
& $python -m pip install -e ./python-backend pytest scipy scikit-learn openpyxl chardet hdbscan lxml
& $python test/phase0/scripts/export_rpc_contract.py
& $python test/phase0/scripts/generate_phase0_fixtures.py
& $python test/phase0/scripts/export_provider_contract.py
& $python test/phase0/scripts/scan_python_semantics.py
& $python test/phase0/scripts/generate_migration_matrix.py
& $python test/phase0/scripts/validate_phase0_assets.py
& $python test/phase0/scripts/validate_rpc_transcripts.py
& $python test/phase0/scripts/benchmark_phase0.py
& test/phase0/scripts/probe_backend.ps1
```

The generators are deterministic except for runtime performance values. Re-generating the fixtures must not introduce real usernames, API keys, personal workspace paths or external user data.

## Comparison rule for Java

Java Phase 1+ tests should consume these files directly. Do not copy and silently edit the expected values in a Java-only fixture tree. Any intentional contract change requires:

1. a documented reason;
2. a versioned schema change;
3. Python/Java/TypeScript compatibility impact;
4. an approved update to the shared golden asset.
