# Phase 0 test and runtime baseline

## Environment

The baseline ran on Windows 11 64-bit, AMD Ryzen 9 8945HX, 32 logical processors and 15.22 GB visible memory. Python 3.13.5 ran exclusively from `python-backend/.venv`; Node was 22.23.1 and npm was 10.9.8. Exact key versions are stored in `test/phase0/baseline/environment.json`.

## Python tests

The environment was created before any Python-side execution. Portable reproduction commands from the repository root are:

```powershell
py -3.13 -m venv python-backend/.venv
$python = 'python-backend/.venv/Scripts/python.exe'
& $python -m pip install --upgrade pip
& $python -m pip install -e ./python-backend pytest scipy scikit-learn openpyxl chardet hdbscan lxml
```

Do not use a global Python or Conda base environment for baseline comparison. Confirm the interpreter and package versions against `test/phase0/baseline/environment.json` before accepting changed numbers.

Command:

```powershell
python-backend/.venv/Scripts/python.exe -m pytest -q
```

Result: **162 passed, 5 failed, 1 warning** out of 167 tests. Pytest reported 19.44 seconds; measured wall time was 21.094 seconds.

| Failing test | Frozen observation |
|---|---|
| `test_bash_prefers_backend_python_on_path` | Shell result exit code is `-1` instead of `0`; project venv Python is not preferred correctly. |
| `test_run_script_file_executes_existing_script_asset` | Metadata returns `script\\demo.py` on Windows instead of normalized `script/demo.py`. |
| `test_running_worker_is_removed_when_folder_disappears` | `stderr.log` remains open, so Windows refuses directory deletion. |
| `test_script_tools_list_read_and_mark_for_edit` | Script list exposes Windows separators instead of protocol separators. |
| `test_worker_start_creates_service_package` | Worker `src_files` contains Windows separators. |

The warning is Pydantic's deprecation of class-based `Config` in `runtime/config.py`.

## Frontend tests

Fixed commands are `npm test -- --reporter=verbose`, `npm run typecheck` and `npm run lint`, using the checked-in package lock and the Node/npm versions in `environment.json`.

Vitest result: **151 passed, 2 failed** out of 153; measured wall time 3.151 seconds.

| Failing test | Frozen observation |
|---|---|
| expected handler count | Registry contains 56 handlers while the test expects 53. |
| invalid `add_raster_from_url` parameters | The tested payload is accepted and returns a raster result instead of `-32602`. |

`npm run typecheck` passed in 7.056 seconds. `npm run lint` exited with code 2 because ESLint 9 cannot find a flat `eslint.config.*` file.

## Sidecar probe

The probe used port 18765 and an ephemeral log directory:

- ready in 5.269 seconds;
- discovered 89 executable tools;
- printed `OPENGIS_WS_TOKEN` before `OPENGIS_READY`;
- `/api/health` returned `{"status":"ok","version":"0.1.0"}`;
- an invalid token returned JSON-RPC error `-32001` and closed;
- a valid token successfully called `rpc.debug.get_log_level` and received `INFO`.
- a normal close reached WebSocket state `Closed`;
- a fresh authenticated socket immediately reconnected and completed another RPC request.

The probe script always stops the Sidecar and validates the temporary directory before deleting its own logs.

## Disconnect and reconnect behavior

The live Phase 0 probe verifies clean disconnect and immediate manual reconnection. Separately, source inspection shows that the TypeScript client rejects all pending requests whenever the socket closes, then retries at 1, 2, 4, 8, 16 and at most 30 seconds, capped at 10 attempts. An explicit user/application `disconnect()` cancels the reconnect timer, clears queued dynamic-layer notifications, rejects pending requests and prevents reconnection. On the server, WebSocket teardown calls `RpcHandler.shutdown()`, cancels the active Agent with reason `websocket_disconnect`, cancels the script runner and then cancels remaining per-message tasks.

## Machine-readable record

The authoritative captured counts and timings are in `test/phase0/baseline/test-results.json`.
