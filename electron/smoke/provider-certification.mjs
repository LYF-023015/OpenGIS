import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..', '..')
const runtimeJava = join(root, 'java-backend', 'opengis-server', 'target', 'runtime', 'bin', 'java.exe')
const java = existsSync(runtimeJava) ? runtimeJava : 'java.exe'
const jar = join(root, 'java-backend', 'opengis-server', 'target', 'opengis-server-0.1.0-SNAPSHOT.jar')
const provider = process.env.OPENGIS_CERT_PROVIDER?.trim()
const apiKey = process.env.OPENGIS_CERT_API_KEY?.trim()
const model = process.env.OPENGIS_CERT_MODEL?.trim()
const baseUrl = process.env.OPENGIS_CERT_BASE_URL?.trim()
const protocol = process.env.OPENGIS_CERT_PROTOCOL?.trim()
const region = process.env.OPENGIS_CERT_REGION?.trim() || 'provider-default'
const timeoutMs = Math.max(1_000, Number(process.env.OPENGIS_CERT_TIMEOUT_MS || 30_000))

if (!provider || !apiKey) {
  throw new Error('Set OPENGIS_CERT_PROVIDER and OPENGIS_CERT_API_KEY before credentialed certification.')
}
if (!existsSync(jar)) throw new Error(`Build the Java server first: ${jar}`)

const logDir = mkdtempSync(join(tmpdir(), 'opengis-provider-cert-'))
let child
try {
  const port = await freePort()
  child = spawn(java, ['-jar', jar, '--host', '127.0.0.1', '--port', String(port), '--log-dir', logDir], {
    cwd: root,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let stdout = ''
  let stderr = ''
  child.stdout.on('data', (chunk) => { stdout += chunk.toString('utf8') })
  child.stderr.on('data', (chunk) => { stderr += chunk.toString('utf8') })
  await waitReady(child, () => stdout, () => stderr)

  const presets = await rpc(port, 'rpc.agent.providers.list', {})
  const preset = presets.providers?.find((candidate) => candidate.id === provider)
  const params = {
    provider,
    api_key: apiKey,
    timeout_ms: timeoutMs,
    ...(model ? { model } : {}),
    ...(baseUrl ? { base_url: baseUrl } : {}),
    ...(protocol ? { protocol } : {}),
  }
  const started = Date.now()
  const result = await rpc(port, 'rpc.agent.test_connection', params)
  const evidence = {
    schemaVersion: 1,
    certifiedAt: new Date().toISOString(),
    platform: process.platform,
    runtime: 'java',
    provider,
    protocol: protocol || preset?.protocol || 'unknown',
    model: result.model || model || preset?.default_model || 'unknown',
    baseUrl: baseUrl || preset?.base_url || 'custom',
    region,
    supportTier: preset?.support_tier || 'custom',
    latencyMs: Date.now() - started,
    checks: {
      credentialsAccepted: result.ok === true,
      quotaAvailableAtCertification: result.ok === true,
      modelAvailableAtCertification: result.ok === true,
      endpointRegionRecorded: region !== 'provider-default' || Boolean(baseUrl),
      apiKeyPersisted: false,
      apiKeyIncludedInEvidence: false,
    },
    result: result.ok === true ? 'passed' : 'failed',
    error: result.ok === true ? null : result.error || 'Provider rejected the test request',
  }
  const evidenceDir = join(root, 'docs', 'migration', 'phase5', 'evidence')
  mkdirSync(evidenceDir, { recursive: true })
  writeFileSync(
    join(evidenceDir, `provider-certification-${provider}.json`),
    `${JSON.stringify(evidence, null, 2)}\n`,
    'utf8',
  )
  if (!result.ok) throw new Error(`Provider certification failed: ${evidence.error}`)
  console.log(JSON.stringify({ status: 'ok', provider, model: evidence.model, latencyMs: evidence.latencyMs }))
} finally {
  if (child && child.exitCode === null) {
    child.kill()
    await Promise.race([
      new Promise((resolveExit) => child.once('exit', resolveExit)),
      new Promise((resolveWait) => setTimeout(resolveWait, 5_000)),
    ])
  }
  rmSync(logDir, { recursive: true, force: true })
}

async function rpc(port, method, params) {
  const response = await fetch(`http://127.0.0.1:${port}/api/rpc`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: `${method}-${Date.now()}`, method, params }),
  })
  const payload = await response.json()
  if (!response.ok || payload.error) {
    throw new Error(payload.error?.message || `RPC ${method} failed with HTTP ${response.status}`)
  }
  return payload.result
}

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

async function waitReady(processHandle, stdout, stderr) {
  const deadline = Date.now() + 30_000
  while (!stdout().includes('OPENGIS_READY') && Date.now() < deadline) {
    if (processHandle.exitCode !== null) {
      throw new Error(`Java Sidecar exited ${processHandle.exitCode}: ${stderr()}`)
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 100))
  }
  if (!stdout().includes('OPENGIS_READY')) throw new Error('Java Sidecar readiness timeout')
}
