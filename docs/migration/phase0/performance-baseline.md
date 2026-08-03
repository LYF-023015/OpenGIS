# Phase 0 performance baseline

These values are comparison anchors, not service-level objectives. They are specific to the machine and versions in `test/phase0/baseline/environment.json`.

| Scenario | Baseline |
|---|---:|
| Python Sidecar cold start to `OPENGIS_READY` | 5.269 s |
| Ordinary `read_file`, median / p95, 50 runs | 0.935 / 1.226 ms |
| Mock first Agent turn, median / p95, 30 runs | 0.147 / 0.240 ms |
| Raster z0 tile with explicit custom stops, median / p95, 10 runs | 10.698 / 12.298 ms |
| Generate 10,000-point GeoJSON | 25.470 ms |
| GeoPandas read 10,000 points | 81.778 ms |
| Generate 100,000-point GeoJSON | 495.642 ms |
| GeoPandas read 100,000 points | 507.376 ms |

The mocked Agent measurement isolates OpenGIS loop/context overhead; it intentionally excludes nondeterministic network and model inference latency. A live Provider baseline requires a named model, endpoint, region and credentials and should be maintained separately from the deterministic migration gate.

The default raster color-ramp path is not benchmarkable in this environment because Matplotlib 3.11.1 removed `matplotlib.cm.get_cmap`, which the current implementation calls. Explicit custom stops exercise the remaining tile path and expose this compatibility defect without altering production code.

Suggested initial Java comparison gates on the same machine:

- cold start: no more than 2x Python until jlink packaging is introduced;
- ordinary Tool median: no more than 2x;
- 10k/100k vector read: no more than 1.5x and no out-of-memory condition;
- raster median: no more than 1.5x;
- all correctness/contract gates take priority over performance.

Machine-readable values are in `test/phase0/baseline/performance.json`. Re-run `benchmark_phase0.py` at least three times before setting release SLOs.
