import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, unlinkSync } from 'node:fs'
import { basename, join, relative, resolve } from 'node:path'
import { listPackage } from '@electron/asar'

const root = resolve(import.meta.dirname, '..', '..')
const evidence = join(root, 'docs', 'migration', 'phase10', 'evidence')
const pythonRoot = join(root, 'python-backend')
const resources = join(root, 'dist', 'win-unpacked', 'resources')
const failures = []

const productionGate = json('production-python-gate.json')
check(productionGate.result === 'passed', 'production Python gate is not passed')
check(productionGate.violations?.length === 0, 'production Python gate contains violations')
check(productionGate.javaDefault === true, 'Java is not recorded as the default runtime')

const assets = json('final-asset-manifest.json')
const terminal = new Set(['converted', 'archived', 'discarded'])
check(assets.assets?.every((asset) => terminal.has(asset.status)), 'an asset is missing a terminal state')
check(assets.assets?.every((asset) => asset.userDataDeleted === false), 'a user asset is marked as deleted')

const ledger = json('migration-ledger-final.json')
check(ledger.total === ledger.entries?.length, 'migration ledger total does not match its entries')
check(ledger.pending === 0, 'migration ledger still has pending entries')
check(ledger.unapprovedDeprecated === 0, 'migration ledger has unapproved deprecated entries')
check(ledger.productionPythonOnly === 0, 'migration ledger still has production python-only entries')

check(existsSync(join(resources, 'java-runtime', 'bin', 'java.exe')), 'packaged Java executable is missing')
check(existsSync(join(resources, 'java-backend', 'opengis-server.jar')), 'packaged Java server is missing')
check(!existsSync(join(resources, 'python-backend')), 'Python backend was packaged')
const packagedAsar = join(resources, 'app.asar')
check(existsSync(packagedAsar), 'packaged app.asar is missing')
if (existsSync(packagedAsar)) {
  check(!listPackage(packagedAsar).some((path) => /pythonManager|python-backend/i.test(path)), 'development-only Python code was packaged')
}
check(existsSync(pythonRoot), 'retained Python backup is missing')

const backup = JSON.parse(readFileSync(join(pythonRoot, 'BACKUP_MANIFEST.json'), 'utf8'))
const checksumLines = readFileSync(join(pythonRoot, 'SHA256SUMS'), 'utf8').trim().split(/\r?\n/).filter(Boolean)
for (const line of checksumLines) {
  const [expected, relativePath] = line.split(/  /, 2)
  const path = join(pythonRoot, ...relativePath.split('/'))
  check(existsSync(path), `Python backup file is missing: ${relativePath}`)
  if (existsSync(path)) check(checksumMatches(path, expected, relativePath, backup.gitTag), `Python backup checksum mismatch: ${relativePath}`)
}

check(backup.deletionForbidden === true, 'Python backup deletion guard is not enabled')
check(backup.fileCount === checksumLines.length, 'Python backup manifest file count differs from SHA256SUMS')
const tag = spawnSync('git', ['rev-parse', '--verify', '--quiet', `${backup.gitTag}^{tree}`], { cwd: root, encoding: 'utf8' })
check(tag.status === 0 && tag.stdout.trim().length === 40, `Python backup tag is missing: ${backup.gitTag}`)
if (tag.status === 0) check(currentBackupTree() === tag.stdout.trim(), 'Python backup tag differs from the retained directory')

const sbom = json('sbom.cdx.json')
check(sbom.bomFormat === 'CycloneDX' && sbom.components?.length > 0, 'SBOM is empty or invalid')
const licenseDecision = json('license-review-decision.json')
check(licenseDecision.manualReviewRequired === false, 'manual license review is still marked as required')
check(licenseDecision.releaseBlocking === false, 'license review is still marked as release-blocking')
const npmAudit = json('npm-production-audit.json')
check(npmAudit.metadata?.vulnerabilities?.total === 0, 'npm production dependency audit is not clean')
const categories = new Set(sbom.components?.flatMap((component) => component.properties ?? [])
  .filter((property) => property.name === 'opengis.category').map((property) => property.value))
for (const category of ['jre', 'gis', 'font', 'pdf', 'third-party-jar']) check(categories.has(category), `SBOM category is missing: ${category}`)
const runtimeInventory = json('windows-runtime-inventory.json')
const serverJar = join(root, 'java-backend', 'opengis-server', 'target', 'opengis-server-0.1.0-SNAPSHOT.jar')
const runtimeRoot = join(root, 'java-backend', 'opengis-server', 'target', 'runtime')
check(sha256(serverJar) === runtimeInventory.serverJarSha256, 'Java server checksum differs from the frozen inventory')
check(directoryDigest(runtimeRoot) === runtimeInventory.javaRuntimeSha256, 'jlink runtime checksum differs from the frozen inventory')
const releaseCycles = json('windows-release-cycles.json')
check(releaseCycles.platform === 'win32' && releaseCycles.cycles?.length === 2, 'two Windows release-candidate cycles were not recorded')
check(releaseCycles.serverSha256 === runtimeInventory.serverJarSha256, 'release cycles used a different Java server build')
check(releaseCycles.cycles?.every((cycle) => cycle.runtime === 'java' && cycle.errors === 0 && cycle.gracefulExit === true), 'a Windows release-candidate cycle did not pass')
check(releaseCycles.cycles?.[1]?.upgradedFrom === '0.1.0-phase9', 'the upgrade-state cycle was not recorded')

if (failures.length) throw new Error(`Phase 10 audit failed:\n- ${failures.join('\n- ')}`)
console.log(JSON.stringify({ status: 'ok', platform: process.platform, ledgerEntries: ledger.total, assets: assets.assets.length, pythonBackupFiles: checksumLines.length, sbomComponents: sbom.components.length, tag: backup.gitTag }))

function json(name) {
  const path = join(evidence, name)
  if (!existsSync(path)) throw new Error(`Evidence is missing: ${basename(path)}`)
  return JSON.parse(readFileSync(path, 'utf8'))
}

function check(condition, message) {
  if (!condition) failures.push(message)
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

function checksumMatches(path, expected, relativePath, tagName) {
  const content = readFileSync(path)
  if (createHash('sha256').update(content).digest('hex') === expected) return true
  // Git may materialize tracked text with LF or CRLF depending on the runner.
  // When that happens, require the normalized content to match the immutable
  // backup tag; binary backup files remain subject to an exact byte checksum.
  if (content.includes(0)) return false
  const tagged = spawnSync('git', ['show', `${tagName}:python-backend/${relativePath}`], {
    cwd: root,
    encoding: null,
  })
  if (tagged.status !== 0 || tagged.stdout.includes(0)) return false
  const normalize = (value) => value.toString('utf8').replaceAll('\r\n', '\n')
  return normalize(content) === normalize(tagged.stdout)
}

function directoryDigest(directory) {
  const hash = createHash('sha256')
  for (const path of walk(directory).sort()) {
    hash.update(relative(directory, path).replaceAll('\\', '/'))
    hash.update('\0')
    hash.update(readFileSync(path))
    hash.update('\0')
  }
  return hash.digest('hex')
}

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? walk(path) : [path]
  })
}

function currentBackupTree() {
  const indexPath = join(root, '.git', `phase10-audit-index-${process.pid}`)
  const env = { ...process.env, GIT_INDEX_FILE: indexPath }
  try {
    runGit(['read-tree', '--empty'], env)
    const files = walk(pythonRoot)
      .filter((path) => {
        const name = relative(pythonRoot, path).replaceAll('\\', '/')
        return !name.startsWith('.venv/')
          && !name.startsWith('.pytest_cache/')
          && !name.includes('/__pycache__/')
          && !name.includes('.egg-info/')
          && !name.endsWith('.pyc')
      })
      .map((path) => relative(root, path).replaceAll('\\', '/'))
    runGit(['add', '-f', '--', ...files], env)
    return runGit(['write-tree'], env).trim()
  } finally {
    if (existsSync(indexPath)) unlinkSync(indexPath)
  }
}

function runGit(args, env) {
  const result = spawnSync('git', args, { cwd: root, env, encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`git ${args[0]} failed: ${result.stderr}`)
  return result.stdout
}
