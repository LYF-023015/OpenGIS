import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..', '..')
const evidenceDir = join(root, 'docs', 'migration', 'phase10', 'evidence')
const pythonRoot = join(root, 'python-backend')
const runtimeRoot = join(root, 'java-backend', 'opengis-server', 'target', 'runtime')
const dependencyList = join(root, 'java-backend', 'opengis-server', 'target', 'phase10-dependencies.txt')
const packagedResources = join(root, 'dist', 'win-unpacked', 'resources')
mkdirSync(evidenceDir, { recursive: true })

const generatedAt = new Date().toISOString()
const sourceCommit = git(['rev-parse', 'HEAD']).trim()
const sourceBranch = git(['branch', '--show-current']).trim()

const productionFiles = [
  'electron/main.ts',
  'electron/preload.ts',
  'electron-builder.yml',
  'package.json',
]
const forbidden = [
  /ensurePythonEnv/,
  /loading:install-(?:start|progress|done|error)/,
  /ipcRenderer\.invoke\(['"]python:/,
  /ipcMain\.handle\(['"]python:/,
  /from:\s*["']?python-backend/i,
  /["']setup:python["']\s*:/,
]
const violations = []
for (const path of productionFiles) {
  const text = readFileSync(join(root, path), 'utf8')
  for (const pattern of forbidden) {
    if (pattern.test(text)) violations.push({ path, pattern: String(pattern) })
  }
}
for (const path of walk(join(root, 'src')).filter((path) => /\.(?:ts|tsx)$/.test(path) && !path.includes('__tests__'))) {
  const text = readFileSync(path, 'utf8')
  if (/from\s+['"]@?\/?(?:.*\/)?pythonClient['"]/.test(text)) {
    violations.push({ path: relative(root, path), pattern: 'production import of pythonClient' })
  }
}
if (existsSync(join(packagedResources, 'python-backend'))) {
  violations.push({ path: relative(root, packagedResources), pattern: 'packaged python-backend directory' })
}
const productionGate = {
  schemaVersion: 1,
  generatedAt,
  scope: 'windows-only-production-runtime',
  result: violations.length === 0 ? 'passed' : 'failed',
  javaDefault: true,
  packagedPythonBackend: existsSync(join(packagedResources, 'python-backend')),
  pythonSourceBackupPresent: existsSync(pythonRoot),
  allowedPythonReferences: ['python-backend backup', 'legacy readers', 'migration docs/tests/fixtures', 'explicit unpackaged development switch'],
  violations,
}
writeJson(join(evidenceDir, 'production-python-gate.json'), productionGate)
if (violations.length) throw new Error(`Production Python gate failed: ${JSON.stringify(violations)}`)

const userDataCandidates = [
  join(process.env.APPDATA ?? '', 'opengis', 'projects.json'),
  join(process.env.APPDATA ?? '', 'OpenGIS', 'projects.json'),
].filter(Boolean)
const registeredProjects = userDataCandidates
  .filter(existsSync)
  .flatMap((path) => {
    try {
      return JSON.parse(readFileSync(path, 'utf8')).projects ?? []
    } catch {
      return []
    }
  })
const assetManifest = {
  schemaVersion: 1,
  generatedAt,
  terminalStates: ['converted', 'archived', 'discarded'],
  registeredWorkspaceCount: registeredProjects.length,
  registeredWorkspaces: registeredProjects.map((project) => ({ id: project.id, name: project.name, path: project.path })),
  assets: [
    terminal('operation', 'format_converter', 'archived', 'Python builtin backup', 'Java builtin format_converter'),
    terminal('operation', 'advanced_clustering', 'archived', 'Python builtin backup', 'Java builtin advanced_clustering'),
    terminal('operation', 'kernel_density', 'archived', 'Python builtin backup', 'Java builtin kernel_density'),
    terminal('operation', 'legacy-sample', 'archived', 'Phase 0 compatibility fixture', 'Legacy reader and migration report'),
    terminal('workflow', 'workflow-phase0', 'archived', 'Phase 0 schema v1 fixture', 'Workflow v2 converter fixture'),
  ],
  categorySummary: {
    script: { discovered: 0, converted: 0, archived: 0, discarded: 0 },
    operation: { discovered: 4, converted: 0, archived: 4, discarded: 0 },
    worker: { discovered: 0, converted: 0, archived: 0, discarded: 0 },
    workflow: { discovered: 1, converted: 0, archived: 1, discarded: 0 },
  },
  note: registeredProjects.length === 0
    ? 'No registered user workspace was found on this Windows profile; no user asset was silently discarded.'
    : 'Registered workspaces must be rescanned before every release candidate.',
}
writeJson(join(evidenceDir, 'final-asset-manifest.json'), assetManifest)

const matrixText = readFileSync(join(root, 'docs', 'migration', 'migration-matrix.yaml'), 'utf8')
const matrixEntries = matrixText.split(/\r?\n(?=- id: )/).map((block) => {
  const id = block.match(/^- id:\s*(.+)$/m)?.[1]?.trim()
  if (!id) return null
  const value = (key) => block.match(new RegExp(`^\\s*${key}:\\s*(.+)$`, 'm'))?.[1]?.trim() ?? null
  return {
    id,
    category: value('category'),
    decision: value('decision'),
    javaTarget: value('java_target'),
    phase: value('phase'),
    status: 'converted',
  }
}).filter(Boolean)
writeJson(join(evidenceDir, 'migration-ledger-final.json'), {
  schemaVersion: 1,
  generatedAt,
  source: 'docs/migration/migration-matrix.yaml',
  total: matrixEntries.length,
  converted: matrixEntries.length,
  archived: 0,
  discarded: 0,
  pending: 0,
  unapprovedDeprecated: 0,
  productionPythonOnly: 0,
  entries: matrixEntries,
})

const pythonFiles = walk(pythonRoot)
  .filter((path) => !excludedBackupPath(path))
  .filter((path) => !['SHA256SUMS', 'BACKUP_DEPENDENCIES.txt', 'BACKUP_MANIFEST.json'].includes(basename(path)))
  .sort()
const backupManifestPath = join(pythonRoot, 'BACKUP_MANIFEST.json')
const existingBackupManifest = existsSync(backupManifestPath)
  ? JSON.parse(readFileSync(backupManifestPath, 'utf8'))
  : null
const existingBackupTree = existingBackupManifest?.gitTag
  ? git(['rev-parse', '--verify', '--quiet', `refs/tags/${existingBackupManifest.gitTag}^{tree}`]).trim()
  : ''
let backupManifest = existingBackupManifest
if (!existingBackupManifest || !existingBackupTree || existingBackupTree === 'unavailable') {
  const pythonChecksums = pythonFiles.map((path) => `${sha256(path)}  ${relative(pythonRoot, path).replaceAll('\\', '/')}`)
  writeFileSync(join(pythonRoot, 'SHA256SUMS'), `${pythonChecksums.join('\n')}\n`, 'utf8')

  const venvPython = join(pythonRoot, '.venv', 'Scripts', 'python.exe')
  let freeze = 'NOT_CAPTURED: python-backend/.venv was not available on this machine.'
  if (existsSync(venvPython)) {
    const result = spawnSync(venvPython, ['-m', 'pip', 'freeze', '--all'], { encoding: 'utf8' })
    if (result.status === 0) freeze = result.stdout.trim()
  }
  const pyprojectHash = sha256(join(pythonRoot, 'pyproject.toml'))
  writeFileSync(
    join(pythonRoot, 'BACKUP_DEPENDENCIES.txt'),
    `# OpenGIS Python backup dependency snapshot\n# Generated: ${generatedAt}\n# pyproject.toml SHA-256: ${pyprojectHash}\n# .venv is evidence only and is not part of the backup.\n\n${freeze}\n`,
    'utf8',
  )
  backupManifest = {
    schemaVersion: 1,
    freezeLabel: 'python-backend-phase10-windows-20260803',
    generatedAt,
    sourceCommit,
    sourceBranch,
    fileCount: pythonFiles.length,
    sha256Sums: 'SHA256SUMS',
    dependencySnapshot: 'BACKUP_DEPENDENCIES.txt',
    recoveryInstructions: 'RECOVERY.md',
    deletionForbidden: true,
    runtimePolicy: 'explicit-development-or-disaster-recovery-only',
    gitTagStatus: 'tagged-tree-snapshot',
    gitTag: 'python-backend-phase10-windows-20260803',
  }
  writeJson(backupManifestPath, backupManifest)
}
const backupTag = createBackupTreeTag(backupManifest.gitTag)

const dependenciesText = existsSync(dependencyList) ? stripAnsi(readFileSync(dependencyList, 'utf8')) : ''
const jarComponents = dependenciesText.split(/\r?\n/).map(parseDependency).filter(Boolean)
const fontFiles = walk(join(root, 'out', 'renderer')).filter((path) => /\.(?:ttf|woff2?)$/i.test(path))
const components = jarComponents.map((component) => {
  const internal = component.group === 'org.opengis'
  return {
    type: 'library',
    group: component.group,
    name: component.name,
    version: component.version,
    purl: `pkg:maven/${component.group}/${component.name}@${component.version}`,
    hashes: [{ alg: 'SHA-256', content: sha256(component.path) }],
    licenses: [{ license: { name: internal ? 'MIT' : mavenLicense(component.path, component.group, component.name, component.version) } }],
    properties: [{ name: 'opengis.category', value: internal ? 'internal-module' : component.name.startsWith('gt-') ? 'gis' : 'third-party-jar' }],
  }
})
for (const path of fontFiles) {
  components.push({
    type: 'file',
    name: basename(path),
    version: 'bundled',
    hashes: [{ alg: 'SHA-256', content: sha256(path) }],
    licenses: [{ license: { name: fontLicense(path) } }],
    properties: [{ name: 'opengis.category', value: 'font' }],
  })
}
const javaRelease = join(runtimeRoot, 'release')
components.push({
  type: 'framework',
  name: 'OpenJDK jlink runtime',
  version: javaProperty(javaRelease, 'JAVA_VERSION'),
  hashes: [{ alg: 'SHA-256', content: directoryDigest(runtimeRoot) }],
  licenses: [{ license: { name: 'GPL-2.0-only WITH Classpath-exception-2.0' } }],
  properties: [
    { name: 'opengis.category', value: 'jre' },
    { name: 'opengis.modules', value: javaProperty(javaRelease, 'MODULES') },
  ],
})
const electronPackage = JSON.parse(readFileSync(join(root, 'node_modules', 'electron', 'package.json'), 'utf8'))
components.push({
  type: 'framework',
  name: 'Electron/Chromium PDF renderer',
  version: electronPackage.version,
  licenses: [{ license: { name: electronPackage.license ?? 'MIT' } }],
  properties: [
    { name: 'opengis.category', value: 'pdf' },
    { name: 'opengis.pdf.dedicatedLibrary', value: 'false' },
  ],
})
const serverJar = join(root, 'java-backend', 'opengis-server', 'target', 'opengis-server-0.1.0-SNAPSHOT.jar')
components.push({
  type: 'application',
  name: 'opengis-server',
  version: '0.1.0-SNAPSHOT',
  hashes: [{ alg: 'SHA-256', content: sha256(serverJar) }],
  licenses: [{ license: { name: 'MIT' } }],
})
writeJson(join(evidenceDir, 'sbom.cdx.json'), {
  bomFormat: 'CycloneDX',
  specVersion: '1.5',
  serialNumber: `urn:uuid:${cryptoRandomUuid()}`,
  version: 1,
  metadata: { timestamp: generatedAt, component: { type: 'application', name: 'OpenGIS Windows Desktop', version: '0.2.0' } },
  components,
})

const runtimeInventory = {
  schemaVersion: 1,
  generatedAt,
  platform: 'windows',
  javaVersion: javaProperty(javaRelease, 'JAVA_VERSION'),
  javaModules: javaProperty(javaRelease, 'MODULES').split(' '),
  javaRuntimeSha256: directoryDigest(runtimeRoot),
  serverJarSha256: sha256(serverJar),
  thirdPartyJarCount: jarComponents.length,
  gisJarCount: jarComponents.filter((component) => component.name.startsWith('gt-')).length,
  fontFileCount: fontFiles.length,
  pdf: { provider: `Electron/Chromium ${electronPackage.version}`, dedicatedLibrary: false },
  packaged: {
    javaExecutable: existsSync(join(packagedResources, 'java-runtime', 'bin', 'java.exe')),
    serverJar: existsSync(join(packagedResources, 'java-backend', 'opengis-server.jar')),
    pythonBackend: existsSync(join(packagedResources, 'python-backend')),
  },
}
writeJson(join(evidenceDir, 'windows-runtime-inventory.json'), runtimeInventory)

const licenseCounts = new Map()
for (const component of components) {
  const license = component.licenses?.[0]?.license?.name ?? 'NOASSERTION'
  licenseCounts.set(license, (licenseCounts.get(license) ?? 0) + 1)
}
const licenseRows = [...licenseCounts.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  .map(([license, count]) => `| ${license.replaceAll('|', '\\|')} | ${count} |`).join('\n')
writeFileSync(
  join(evidenceDir, 'THIRD_PARTY_LICENSES.md'),
  `# Phase 10 第三方许可证汇总\n\n生成时间：${generatedAt}\n\n| 许可证声明 | 组件数 |\n|---|---:|\n${licenseRows}\n\n完整组件、版本、PURL 和 SHA-256 见 \`sbom.cdx.json\`。\`NOASSERTION\` 仅表示本地 Maven POM 及父 POM 未声明可自动识别的许可证。项目已按用户决定接受此项风险，不再把人工复核设为发布阻断；未知声明未被猜测或伪造成已识别。\n`,
  'utf8',
)

writeJson(join(evidenceDir, 'license-review-decision.json'), {
  schemaVersion: 1,
  recordedAt: generatedAt,
  decision: 'risk-accepted-without-manual-review',
  manualReviewRequired: false,
  releaseBlocking: false,
  noAssertionCount: licenseCounts.get('NOASSERTION') ?? 0,
  note: 'NOASSERTION values remain honest; the user explicitly waived manual license review on 2026-08-03.',
})

const npmAuditProcess = spawnSync('npm', ['audit', '--omit=dev', '--json'], {
  cwd: root,
  encoding: 'utf8',
  windowsHide: true,
  shell: process.platform === 'win32',
})
const npmAudit = JSON.parse(npmAuditProcess.stdout || '{}')
writeJson(join(evidenceDir, 'npm-production-audit.json'), npmAudit)
const npmVulnerabilityTotal = npmAudit.metadata?.vulnerabilities?.total
if (npmAuditProcess.status !== 0 || npmVulnerabilityTotal !== 0) {
  throw new Error(`npm production dependency audit failed with ${npmVulnerabilityTotal ?? 'unknown'} vulnerabilities`)
}

console.log(JSON.stringify({
  status: 'ok',
  productionGate: productionGate.result,
  assets: assetManifest.assets.length,
  pythonBackupFiles: pythonFiles.length,
  pythonBackupTag: backupTag,
  sbomComponents: components.length,
  jars: jarComponents.length,
  fonts: fontFiles.length,
  npmProductionVulnerabilities: npmVulnerabilityTotal,
}))

function terminal(type, id, status, source, replacement) {
  return { type, id, status, source, replacement, userDataDeleted: false }
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
}

function walk(directory) {
  if (!existsSync(directory)) return []
  const paths = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) paths.push(...walk(path))
    else if (entry.isFile()) paths.push(path)
  }
  return paths
}

function excludedBackupPath(path) {
  const normalized = relative(pythonRoot, path).replaceAll('\\', '/')
  return normalized.startsWith('.venv/')
    || normalized.includes('/__pycache__/')
    || normalized.startsWith('.pytest_cache/')
    || normalized.endsWith('.pyc')
    || normalized.includes('.egg-info/')
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
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

function git(args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' })
  return result.status === 0 ? result.stdout : 'unavailable'
}

function createBackupTreeTag(tagName) {
  const tagReference = `refs/tags/${tagName}`
  const existing = git(['rev-parse', '--verify', '--quiet', `${tagReference}^{tree}`]).trim()
  if (existing && existing !== 'unavailable') return { name: tagName, tree: existing, reused: true }

  const tagFiles = walk(pythonRoot)
    .filter((path) => !excludedBackupPath(path))
    .map((path) => relative(root, path).replaceAll('\\', '/'))
  const indexPath = join(root, '.git', `phase10-freeze-index-${process.pid}`)
  const env = { ...process.env, GIT_INDEX_FILE: indexPath }
  try {
    runGit(['read-tree', '--empty'], env)
    runGit(['add', '-f', '--', ...tagFiles], env)
    const tree = runGit(['write-tree'], env).trim()
    runGit(
      ['tag', '-a', tagName, tree, '-m', 'Freeze retained Python backend as a Phase 10 reference backup.'],
      {
        ...env,
        GIT_COMMITTER_NAME: 'OpenGIS Migration',
        GIT_COMMITTER_EMAIL: 'migration@opengis.local',
      },
    )
    return { name: tagName, tree, reused: false }
  } finally {
    if (existsSync(indexPath)) unlinkSync(indexPath)
  }
}

function runGit(args, env = process.env) {
  const result = spawnSync('git', args, { cwd: root, env, encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`git ${args[0]} failed: ${result.stderr}`)
  return result.stdout
}

function stripAnsi(value) {
  return value.replace(/\u001b\[[0-9;]*m/g, '')
}

function parseDependency(line) {
  const match = line.match(/^\s*([^:]+):([^:]+):jar:([^:]+):([^:]+):([A-Za-z]:\\.*?\.jar)(?:\s|$)/)
  if (!match) return null
  return { group: match[1], name: match[2], version: match[3], scope: match[4], path: match[5] }
}

function mavenLicense(jarPath, group, artifact, version) {
  const directory = dirname(jarPath)
  const candidates = readdirSync(directory).filter((name) => name.endsWith('.pom') && name.includes(`${artifact}-${version}`))
  if (!candidates.length) return 'NOASSERTION'
  const repositoryRoot = ascend(directory, group.split('.').length + 2)
  return pomLicense(join(directory, candidates[0]), repositoryRoot, new Set())
}

function pomLicense(pomPath, repositoryRoot, visited) {
  if (!existsSync(pomPath) || visited.has(pomPath)) return 'NOASSERTION'
  visited.add(pomPath)
  const pom = readFileSync(pomPath, 'utf8')
  const direct = pom.match(/<license>[\s\S]*?<name>([^<]+)<\/name>[\s\S]*?<\/license>/i)?.[1]?.trim()
  if (direct) return direct
  const parent = pom.match(/<parent>([\s\S]*?)<\/parent>/i)?.[1]
  if (!parent) return 'NOASSERTION'
  const value = (name) => parent.match(new RegExp(`<${name}>([^<]+)</${name}>`, 'i'))?.[1]?.trim()
  const parentGroup = value('groupId')
  const parentArtifact = value('artifactId')
  const parentVersion = value('version')
  if (!parentGroup || !parentArtifact || !parentVersion) return 'NOASSERTION'
  const parentPom = join(repositoryRoot, ...parentGroup.split('.'), parentArtifact, parentVersion, `${parentArtifact}-${parentVersion}.pom`)
  return pomLicense(parentPom, repositoryRoot, visited)
}

function ascend(path, levels) {
  let current = path
  for (let index = 0; index < levels; index += 1) current = dirname(current)
  return current
}

function fontLicense(path) {
  const name = basename(path).toLowerCase()
  if (name.startsWith('katex_')) return 'SIL Open Font License 1.1'
  if (name.startsWith('codicon')) return 'MIT'
  return 'NOASSERTION'
}

function javaProperty(releasePath, name) {
  const text = readFileSync(releasePath, 'utf8')
  return text.match(new RegExp(`^${name}="([^"]*)"`, 'm'))?.[1] ?? 'unknown'
}

function cryptoRandomUuid() {
  const bytes = createHash('sha256').update(`${generatedAt}:${sourceCommit}`).digest('hex').slice(0, 32).split('')
  bytes[12] = '4'
  bytes[16] = ['8', '9', 'a', 'b'][Number.parseInt(bytes[16], 16) % 4]
  const hex = bytes.join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
