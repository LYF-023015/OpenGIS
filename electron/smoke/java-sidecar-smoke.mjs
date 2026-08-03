import { app } from 'electron'
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const repoRoot = resolve(import.meta.dirname, '..', '..')
const runtimeJava = join(
  repoRoot,
  'java-backend',
  'opengis-server',
  'target',
  'runtime',
  'bin',
  process.platform === 'win32' ? 'java.exe' : 'java',
)
const systemJava = process.platform === 'win32' ? 'java.exe' : 'java'
const java = existsSync(runtimeJava) ? runtimeJava : systemJava
const jar = join(repoRoot, 'java-backend', 'opengis-server', 'target', 'opengis-server-0.1.0-SNAPSHOT.jar')
const logDir = mkdtempSync(join(tmpdir(), 'opengis-java-electron-smoke-'))

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => resolvePort(address.port))
    })
  })
}

function waitForExit(child, timeoutMs) {
  return Promise.race([
    new Promise((resolveExit) => child.once('exit', resolveExit)),
    new Promise((_, reject) => setTimeout(() => reject(new Error('Java Sidecar stop timeout')), timeoutMs)),
  ])
}

let child
let exitCode = 0
try {
  if (!existsSync(jar)) throw new Error(`Build the Java server first: ${jar}`)
  const port = await freePort()
  child = spawn(java, ['-jar', jar, '--host', '127.0.0.1', '--port', String(port), '--log-dir', logDir], {
    cwd: repoRoot,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let stdout = ''
  let stderr = ''
  child.stdout.on('data', (chunk) => { stdout += chunk.toString('utf8') })
  child.stderr.on('data', (chunk) => { stderr += chunk.toString('utf8') })

  const deadline = Date.now() + 30_000
  while (!stdout.includes('OPENGIS_READY') && Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Java Sidecar exited ${child.exitCode}: ${stderr}`)
    await new Promise((resolveWait) => setTimeout(resolveWait, 100))
  }
  const tokenIndex = stdout.indexOf('OPENGIS_WS_TOKEN=')
  const readyIndex = stdout.indexOf('OPENGIS_READY')
  if (tokenIndex < 0 || readyIndex < 0 || tokenIndex >= readyIndex) {
    throw new Error(`Invalid startup stdout ordering:\n${stdout}\n${stderr}`)
  }
  const response = await fetch(`http://127.0.0.1:${port}/api/health`)
  const health = await response.json()
  if (!response.ok || health.status !== 'ok' || health.version !== '0.1.0') {
    throw new Error(`Invalid health response: ${response.status} ${JSON.stringify(health)}`)
  }
  const rpcResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase2-ping',
      method: 'rpc.system.ping',
      params: {},
    }),
  })
  const rpc = await rpcResponse.json()
  if (
    !rpcResponse.ok
    || rpc.id !== 'electron-phase2-ping'
    || rpc.result?.status !== 'ok'
    || rpc.result?.runtime !== 'java'
    || rpc.result?.protocol_version !== '3.0'
  ) {
    throw new Error(`Invalid RPC response: ${rpcResponse.status} ${JSON.stringify(rpc)}`)
  }
  const phase3Response = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase3-runs',
      method: 'rpc.runs.list',
      params: {
        workspace_path: join(repoRoot, 'test', 'phase0', 'fixtures', 'opengis-workspace'),
      },
    }),
  })
  const phase3Rpc = await phase3Response.json()
  if (
    !phase3Response.ok
    || phase3Rpc.id !== 'electron-phase3-runs'
    || !Array.isArray(phase3Rpc.result?.runs)
    || phase3Rpc.result.runs[0]?.run_id !== 'run-phase0-001'
  ) {
    throw new Error(`Invalid Phase 3 RPC response: ${phase3Response.status} ${JSON.stringify(phase3Rpc)}`)
  }
  const fixtureWorkspace = join(repoRoot, 'test', 'phase0', 'fixtures', 'opengis-workspace')
  const phase4CatalogResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase4-tools',
      method: 'rpc.tool.list',
      params: {},
    }),
  })
  const phase4CatalogRpc = await phase4CatalogResponse.json()
  if (
    !phase4CatalogResponse.ok
    || !Array.isArray(phase4CatalogRpc.result?.tools)
    || phase4CatalogRpc.result.tools.length < 50
    || !phase4CatalogRpc.result.tools.some((tool) => tool.name === 'read_file')
  ) {
    throw new Error(`Invalid Phase 4 catalog: ${phase4CatalogResponse.status} ${JSON.stringify(phase4CatalogRpc)}`)
  }
  const phase4ExecuteResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase4-read',
      method: 'rpc.tool.execute',
      params: {
        workspace_path: fixtureWorkspace,
        name: 'read_file',
        args: { file_path: '.opengis/sessions.json', limit: 20 },
      },
    }),
  })
  const phase4ExecuteRpc = await phase4ExecuteResponse.json()
  if (
    !phase4ExecuteResponse.ok
    || phase4ExecuteRpc.result?.success !== true
    || !phase4ExecuteRpc.result?.data?.output?.includes('conversation-phase0-001')
  ) {
    throw new Error(`Invalid Phase 4 execution: ${phase4ExecuteResponse.status} ${JSON.stringify(phase4ExecuteRpc)}`)
  }
  const phase5ProvidersResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase5-providers',
      method: 'rpc.agent.providers.list',
      params: {},
    }),
  })
  const phase5ProvidersRpc = await phase5ProvidersResponse.json()
  if (
    !phase5ProvidersResponse.ok
    || phase5ProvidersRpc.result?.providers?.length !== 24
    || !phase5ProvidersRpc.result.providers.some((provider) => provider.id === 'anthropic')
    || !phase5ProvidersRpc.result.providers.some((provider) => provider.id === 'openai')
  ) {
    throw new Error(`Invalid Phase 5 Provider catalog: ${phase5ProvidersResponse.status} ${JSON.stringify(phase5ProvidersRpc)}`)
  }
  const phase5CacheResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase5-cache',
      method: 'rpc.agent.cache.stats',
      params: {},
    }),
  })
  const phase5CacheRpc = await phase5CacheResponse.json()
  if (!phase5CacheResponse.ok || phase5CacheRpc.result?.requests !== 0) {
    throw new Error(`Invalid Phase 5 cache diagnostics: ${phase5CacheResponse.status} ${JSON.stringify(phase5CacheRpc)}`)
  }
  const phase6InspectResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase6-inspect',
      method: 'rpc.workflow.inspect',
      params: {
        workflow: {
          schemaVersion: 1,
          id: 'phase6-python-workflow',
          name: 'Phase 6 Python Workflow',
          nodes: [{
            id: 'legacy-python',
            title: 'Legacy Python',
            scriptPath: 'scripts/legacy.py',
            hooks: [{ expression: 'result is not None' }],
          }],
          edges: [],
        },
      },
    }),
  })
  const phase6InspectRpc = await phase6InspectResponse.json()
  const phase6IssueCodes = phase6InspectRpc.result?.issues?.map((issue) => issue.code) ?? []
  if (
    !phase6InspectResponse.ok
    || phase6InspectRpc.result?.status !== 'manual_required'
    || !phase6IssueCodes.includes('python_script_reference')
    || !phase6IssueCodes.includes('python_hook')
  ) {
    throw new Error(`Invalid Phase 6 migration inspection: ${phase6InspectResponse.status} ${JSON.stringify(phase6InspectRpc)}`)
  }
  const phase6Workflow = {
    schemaVersion: 2,
    id: 'phase6-smoke',
    name: 'Phase 6 Smoke',
    description: 'Electron to Java workflow persistence smoke test',
    nodes: [{
      id: 'read-session-index',
      title: 'Read session index',
      description: 'Exercises the structured tool_call node schema.',
      type: 'tool_call',
      execution: { kind: 'tool_call', ref: 'read_file' },
      inputs: [],
      outputs: [],
      params: { file_path: '.opengis/sessions.json', limit: 20 },
      position: { x: 0, y: 0 },
      conditions: [],
      retryPolicy: { maxAttempts: 1, backoffMs: 0 },
    }],
    edges: [],
  }
  const phase6SaveResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase6-save',
      method: 'rpc.workflow.save',
      params: { workspace_path: logDir, workflow: phase6Workflow },
    }),
  })
  const phase6SaveRpc = await phase6SaveResponse.json()
  if (!phase6SaveResponse.ok || phase6SaveRpc.result?.workflow_id !== phase6Workflow.id) {
    throw new Error(`Invalid Phase 6 workflow save: ${phase6SaveResponse.status} ${JSON.stringify(phase6SaveRpc)}`)
  }
  const phase6LoadResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase6-load',
      method: 'rpc.workflow.load',
      params: { workspace_path: logDir, workflow_id: phase6Workflow.id },
    }),
  })
  const phase6LoadRpc = await phase6LoadResponse.json()
  if (!phase6LoadResponse.ok || phase6LoadRpc.result?.workflow?.schemaVersion !== 2) {
    throw new Error(`Invalid Phase 6 workflow load: ${phase6LoadResponse.status} ${JSON.stringify(phase6LoadRpc)}`)
  }
  const phase6QueueResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase6-queue',
      method: 'rpc.agent.queue.submit',
      params: {
        workspace_path: logDir,
        message: 'Phase 6 queue smoke test',
        conversation_id: 'phase6-smoke',
        metadata: { idempotency_key: 'phase6-smoke' },
      },
    }),
  })
  const phase6QueueRpc = await phase6QueueResponse.json()
  if (!phase6QueueResponse.ok || phase6QueueRpc.result?.status !== 'queued') {
    throw new Error(`Invalid Phase 6 queue submit: ${phase6QueueResponse.status} ${JSON.stringify(phase6QueueRpc)}`)
  }
  const phase6CancelResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase6-cancel',
      method: 'rpc.agent.queue.cancel',
      params: { workspace_path: logDir, queue_id: phase6QueueRpc.result.queue_id },
    }),
  })
  const phase6CancelRpc = await phase6CancelResponse.json()
  if (!phase6CancelResponse.ok || phase6CancelRpc.result?.status !== 'cancelled') {
    throw new Error(`Invalid Phase 6 queue cancellation: ${phase6CancelResponse.status} ${JSON.stringify(phase6CancelRpc)}`)
  }
  const phase7Source = join(logDir, 'phase7-smoke.geojson')
  writeFileSync(phase7Source, JSON.stringify({
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [121.4, 31.2] },
      properties: { name: 'Phase 7' },
    }],
  }))
  const phase7CapabilitiesResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase7-capabilities',
      method: 'rpc.gis.capabilities',
      params: {},
    }),
  })
  const phase7CapabilitiesRpc = await phase7CapabilitiesResponse.json()
  if (
    !phase7CapabilitiesResponse.ok
    || phase7CapabilitiesRpc.result?.runtime !== 'java'
    || phase7CapabilitiesRpc.result?.geotools_version !== '35.0'
    || !Array.isArray(phase7CapabilitiesRpc.result?.formats)
  ) {
    throw new Error(`Invalid Phase 7 capabilities: ${phase7CapabilitiesResponse.status} ${JSON.stringify(phase7CapabilitiesRpc)}`)
  }
  const phase7InspectResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase7-inspect',
      method: 'rpc.gis.file.inspect',
      params: { workspace_path: logDir, path: 'phase7-smoke.geojson' },
    }),
  })
  const phase7InspectRpc = await phase7InspectResponse.json()
  if (
    !phase7InspectResponse.ok
    || phase7InspectRpc.result?.formatName !== 'GeoJSON'
    || phase7InspectRpc.result?.featureCount !== 1
  ) {
    throw new Error(`Invalid Phase 7 GIS inspection: ${phase7InspectResponse.status} ${JSON.stringify(phase7InspectRpc)}`)
  }
  const phase9PivotResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase9-pivot',
      method: 'rpc.analysis.pivot',
      params: {
        kind: 'table',
        columns: ['value', 'group'],
        rows: [
          { value: 1, group: 'a' },
          { value: 2, group: 'a' },
          { value: 3, group: 'b' },
          { value: 4, group: 'b' },
        ],
        total_rows: 4,
      },
    }),
  })
  const phase9PivotRpc = await phase9PivotResponse.json()
  if (
    !phase9PivotResponse.ok
    || !Array.isArray(phase9PivotRpc.result?.stats)
    || phase9PivotRpc.result.stats.length !== 2
    || !phase9PivotRpc.result?.summary?.includes('Java analyzed')
  ) {
    throw new Error(`Invalid Phase 9 Pivot RPC: ${phase9PivotResponse.status} ${JSON.stringify(phase9PivotRpc)}`)
  }
  const phase8OperationsResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase8-operations',
      method: 'rpc.operations.list',
      params: { workspace_path: logDir },
    }),
  })
  const phase8OperationsRpc = await phase8OperationsResponse.json()
  const phase8OperationIds = phase8OperationsRpc.result?.operations?.map((operation) => operation.id) ?? []
  if (
    !phase8OperationsResponse.ok
    || !phase8OperationIds.includes('format_converter')
    || !phase8OperationIds.includes('advanced_clustering')
    || !phase8OperationIds.includes('kernel_density')
  ) {
    throw new Error(`Invalid Phase 8 Operation catalog: ${phase8OperationsResponse.status} ${JSON.stringify(phase8OperationsRpc)}`)
  }
  const phase8ScriptResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase8-script',
      method: 'rpc.code.run_script',
      params: {
        workspace_path: logDir,
        run_id: 'electron-phase8-script',
        code: `package smoke;
import java.util.Map;
import org.opengis.script.sdk.OpenGisScript;
import org.opengis.script.sdk.ScriptContext;
public final class SmokeScript implements OpenGisScript {
  public Object run(ScriptContext context, Map<String,Object> params) {
    System.out.println("phase8-electron");
    return Map.of("phase", "8B");
  }
}`,
      },
    }),
  })
  const phase8ScriptRpc = await phase8ScriptResponse.json()
  if (
    !phase8ScriptResponse.ok
    || phase8ScriptRpc.result?.ok !== true
    || phase8ScriptRpc.result?.output?.phase !== '8B'
  ) {
    throw new Error(`Invalid Phase 8 Script execution: ${phase8ScriptResponse.status} ${JSON.stringify(phase8ScriptRpc)}`)
  }
  const phase8WorkerResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase8-worker',
      method: 'rpc.worker.start',
      params: {
        workspace_path: logDir,
        name: 'Electron Phase 8 Worker',
        code: `package smoke;
import java.time.Duration;
import java.util.Map;
import org.opengis.script.sdk.OpenGisWorker;
import org.opengis.script.sdk.WorkerContext;
public final class SmokeWorker implements OpenGisWorker {
  private volatile boolean stopped;
  public void start(WorkerContext context) throws Exception {
    while (!stopped) context.sleep(Duration.ofMillis(50));
  }
  public void stop() { stopped = true; }
  public Map<String,Object> health() { return Map.of("status", "running"); }
}`,
        entry_class: 'smoke.SmokeWorker',
      },
    }),
  })
  const phase8WorkerRpc = await phase8WorkerResponse.json()
  const phase8WorkerId = phase8WorkerRpc.result?.worker?.worker_id
  if (!phase8WorkerResponse.ok || typeof phase8WorkerId !== 'string') {
    throw new Error(`Invalid Phase 8 Worker start: ${phase8WorkerResponse.status} ${JSON.stringify(phase8WorkerRpc)}`)
  }
  const phase8PauseResponse = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 'electron-phase8-worker-pause',
      method: 'rpc.worker.pause',
      params: { workspace_path: logDir, worker_id: phase8WorkerId, reason: 'smoke_complete' },
    }),
  })
  const phase8PauseRpc = await phase8PauseResponse.json()
  if (!phase8PauseResponse.ok || phase8PauseRpc.result?.worker?.status !== 'paused') {
    throw new Error(`Invalid Phase 8 Worker pause: ${phase8PauseResponse.status} ${JSON.stringify(phase8PauseRpc)}`)
  }
  console.log(JSON.stringify({
    status: 'ok',
    electron: process.versions.electron,
    java,
    port,
    health,
    rpc,
    phase3Rpc,
    phase4: {
      toolCount: phase4CatalogRpc.result.tools.length,
      readStatus: phase4ExecuteRpc.result.status,
      readTruncated: phase4ExecuteRpc.result.data.truncated,
    },
    phase5: {
      providerCount: phase5ProvidersRpc.result.providers.length,
      cacheRequests: phase5CacheRpc.result.requests,
    },
    phase6: {
      inspectionStatus: phase6InspectRpc.result.status,
      issueCodes: phase6IssueCodes,
      workflowVersion: phase6LoadRpc.result.workflow.schemaVersion,
      queueStatus: phase6CancelRpc.result.status,
    },
    phase7: {
      runtime: phase7CapabilitiesRpc.result.runtime,
      geotoolsVersion: phase7CapabilitiesRpc.result.geotools_version,
      formatCount: phase7CapabilitiesRpc.result.formats.length,
      inspectedFormat: phase7InspectRpc.result.formatName,
      featureCount: phase7InspectRpc.result.featureCount,
    },
    phase8: {
      operationIds: phase8OperationIds,
      scriptStatus: phase8ScriptRpc.result.status,
      workerStatus: phase8PauseRpc.result.worker.status,
    },
    phase9: {
      pivotFields: phase9PivotRpc.result.stats.length,
      pivotEngine: 'java',
    },
  }))
} catch (error) {
  exitCode = 1
  console.error(error instanceof Error ? error.stack : String(error))
} finally {
  if (child && child.exitCode === null) {
    child.kill()
    try { await waitForExit(child, 5_000) } catch { child.kill('SIGKILL') }
  }
  rmSync(logDir, { recursive: true, force: true })
  app.exit(exitCode)
}
