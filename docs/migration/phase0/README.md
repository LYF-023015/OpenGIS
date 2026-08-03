# Phase 0 completion record

Status: **complete with recorded pre-existing failures**

Baseline date: 2026-08-02

Source commit before Phase 0 artifacts: `1e59165373fecb4f8cc7b58d74de2733905f4044`

Phase 0 freezes the current Python/TypeScript behavior before Java implementation begins. A baseline is allowed to contain failures; concealing or fixing unrelated behavior while capturing the baseline would make later comparisons unreliable.

## Completion checklist

- [x] Created isolated `python-backend/.venv` before running any Python-side test or generator.
- [x] Installed the backend, pytest and Operation dependencies in that environment.
- [x] Ran and recorded Python tests.
- [x] Ran and recorded Vitest, TypeScript typecheck and ESLint status.
- [x] Probed Sidecar startup, stdout ordering, health and valid/invalid WebSocket authentication.
- [x] Exported 47 Renderer-to-backend request methods.
- [x] Exported per-method parameter/default examples, result matchers and JSON-RPC errors for all 47 methods.
- [x] Exported 56 backend-to-Renderer registered handlers.
- [x] Saved 20 representative protocol transcripts and a JSON-RPC envelope schema; replayed 17 request cases and structurally validated 4 server-push messages.
- [x] Generated deterministic vector, raster and edge-case GIS fixtures.
- [x] Generated Python golden summaries for format conversion, clustering and kernel density.
- [x] Generated a fully synthetic `.opengis` workspace and validated it with current Python Readers.
- [x] Inventoried 16 workspace `.opengis` store/cache families and added anonymous fixtures for every persistent family.
- [x] Captured startup, ordinary Tool, mocked first Agent turn, 10k/100k feature and raster tile baselines.
- [x] Preserved deterministic compressed 10k and 100k GIS fixtures with SHA-256 golden records.
- [x] Recorded migration decisions and offline wire fixtures for all 24 public Providers.
- [x] Scanned 44 Python-semantic consumers in source, Electron, build configuration and documentation.
- [x] Generated the 347-entry authoritative migration matrix.
- [x] Added ADR-001 and ADR-002.

## Exit decision

Phase 1 may begin. The following are **known baseline defects**, not Phase 0 blockers:

- Python: 5 failing tests, mainly Windows path separators, Worker log handles and virtual-environment PATH selection.
- Frontend: 2 failing RPC handler tests.
- ESLint: configuration missing for ESLint 9.
- Raster default ramps fail with Matplotlib 3.11.1 because `matplotlib.cm.get_cmap` is no longer present; the deterministic raster benchmark uses explicit custom stops.
- LiteLLM tries to fetch its remote model cost map during cold startup and falls back locally when the configured SOCKS path lacks `socksio`.

These defects should receive separate issues. Phase 1 Java code must match the intended contract rather than reproduce an accidental Windows separator or leaked file handle.

## Deliverables

- [Baseline report](baseline-report.md)
- [RPC contract](rpc-contract.md)
- [Data contract](data-contract.md)
- [Performance baseline](performance-baseline.md)
- [Provider contract](provider-contract.md)
- [Python semantic inventory](python-semantic-inventory.md)
- [Migration matrix guide](migration-matrix.md)
- [Machine-readable migration matrix](../migration-matrix.yaml)
- [`test/phase0`](../../../test/phase0/README.md)
- [ADR-001](../../adr/001-modular-monolith-and-strangler-migration.md)
- [ADR-002](../../adr/002-java-spring-maven-baseline.md)
