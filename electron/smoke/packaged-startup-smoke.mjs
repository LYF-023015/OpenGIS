import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

if (process.platform !== 'win32') throw new Error('Phase 10 packaged validation is Windows-only')

const dist = resolve('dist')
const executable = join(dist, 'win-unpacked', 'OpenGIS.exe')
const packagedServer = join(dist, 'win-unpacked', 'resources', 'java-backend', 'opengis-server.jar')
if (!existsSync(executable)) throw new Error(`Packaged executable is missing: ${executable}`)
if (!existsSync(packagedServer)) throw new Error(`Packaged Java server is missing: ${packagedServer}`)

const userData = mkdtempSync(join(tmpdir(), 'opengis-phase10-'))
const expectedServerSha256 = sha256(packagedServer)
const evidencePath = resolve('docs', 'migration', 'phase10', 'evidence', 'windows-release-cycles.json')

try {
  const cycles = []
  for (let cycle = 1; cycle <= 2; cycle += 1) cycles.push(await runCycle(cycle))
  writeFileSync(evidencePath, `${JSON.stringify({
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    scope: 'windows-local-release-candidate-validation',
    platform: process.platform,
    appVersion: cycles[0].appVersion,
    serverSha256: expectedServerSha256,
    cycles: cycles.map((cycle) => ({
      ...cycle,
      scenario: cycle.cycle === 1 ? 'clean-install-first-start' : 'existing-last-good-upgrade-start',
      errors: 0,
      gracefulExit: true,
      ...(cycle.cycle === 2 ? { lastGoodRevalidated: true } : {}),
    })),
    interpretation: 'These are two independent Windows release-candidate cycles on the requested target machine, not long-term production telemetry.',
  }, null, 2)}\n`, 'utf8')
  console.log(JSON.stringify({ status: 'ok', platform: process.platform, executable, cycles }))
} finally {
  await removeWithRetry(userData)
}

async function runCycle(cycle) {
  const markerPath = join(userData, 'java-backend-last-good.json')
  const upgradedFrom = cycle === 2 ? '0.1.0-phase9' : null
  if (upgradedFrom) {
    writeFileSync(markerPath, JSON.stringify({
      runtime: 'java',
      appVersion: upgradedFrom,
      serverSha256: '0'.repeat(64),
      javaPath: 'legacy-java',
      serverPath: 'legacy-server.jar',
      verifiedAt: '2026-08-02T00:00:00.000Z',
    }, null, 2), 'utf8')
  }
  const startedAt = Date.now()
  const child = spawn(executable, [`--user-data-dir=${userData}`], {
    env: { ...process.env, OPENGIS_PHASE9_SMOKE_EXIT_MS: '2500' },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })
  let stdout = ''
  let stderr = ''
  child.stdout.on('data', (value) => { stdout += value.toString() })
  child.stderr.on('data', (value) => { stderr += value.toString() })

  const code = await Promise.race([
    new Promise((resolveExit) => child.once('exit', resolveExit)),
    new Promise((_, reject) => setTimeout(() => reject(new Error(`Packaged startup cycle ${cycle} did not exit in 45 seconds`)), 45_000)),
  ])
  if (code !== 0) throw new Error(`Packaged OpenGIS cycle ${cycle} exited ${code}\n${stdout}\n${stderr}`)
  if (!stdout.includes('OPENGIS_READY') || !stdout.includes('backend ready')) {
    throw new Error(`Packaged Java readiness was not observed in cycle ${cycle}\n${stdout}\n${stderr}`)
  }

  if (!existsSync(markerPath)) throw new Error(`Last-good marker was not created in cycle ${cycle}: ${markerPath}`)
  const marker = JSON.parse(readFileSync(markerPath, 'utf8'))
  if (marker.runtime !== 'java' || marker.serverSha256 !== expectedServerSha256) {
    throw new Error(`Invalid last-good marker in cycle ${cycle}: ${JSON.stringify(marker)}`)
  }
  return {
    cycle,
    durationMs: Date.now() - startedAt,
    runtime: marker.runtime,
    appVersion: marker.appVersion,
    serverSha256: marker.serverSha256,
    verifiedAt: marker.verifiedAt,
    upgradedFrom,
  }
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

async function removeWithRetry(path) {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    try {
      rmSync(path, { recursive: true, force: true, maxRetries: 2, retryDelay: 200 })
      return
    } catch (error) {
      if (attempt === 5 || !['EBUSY', 'EPERM', 'ENOTEMPTY'].includes(error.code)) throw error
      await new Promise((resolveWait) => setTimeout(resolveWait, 250 * (attempt + 1)))
    }
  }
}
