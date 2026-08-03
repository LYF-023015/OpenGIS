import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const javaBackend = join(root, 'java-backend')
const server = join(javaBackend, 'opengis-server')
const target = join(server, 'target')
const runtime = join(target, 'runtime')
const libraries = join(target, 'jlink-libs')
const wrapper = join(javaBackend, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')

if (!existsSync(wrapper)) throw new Error(`Maven Wrapper is missing: ${wrapper}`)
if (!resolve(runtime).startsWith(`${resolve(target)}${sep}`)) throw new Error(`Unsafe runtime output: ${runtime}`)

run(wrapper, ['-q', '-f', join(javaBackend, 'pom.xml'), '-pl', 'opengis-server', '-am', 'install', '-DskipTests'])
run(wrapper, ['-q', '-f', join(javaBackend, 'pom.xml'), '-pl', 'opengis-server', 'dependency:copy-dependencies', '-DincludeScope=runtime', '-DoutputDirectory=target/jlink-libs'])

const javaCommand = process.platform === 'win32' ? 'java.exe' : 'java'
const javaSettings = spawnSync(javaCommand, ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
if (javaSettings.error) throw javaSettings.error
const properties = `${javaSettings.stdout ?? ''}\n${javaSettings.stderr ?? ''}`
const javaHome = process.env.JAVA_HOME || properties.match(/^\s*java\.home\s*=\s*(.+)$/m)?.[1]?.trim()
if (!javaHome) throw new Error('Unable to resolve a JDK from JAVA_HOME or java.home')

const tool = (name) => join(javaHome, 'bin', process.platform === 'win32' ? `${name}.exe` : name)
const jars = readdirSync(libraries).filter((name) => name.endsWith('.jar')).map((name) => join(libraries, name))
const detected = execFileSync(tool('jdeps'), [
  '--multi-release', 'base',
  '--ignore-missing-deps',
  '--recursive',
  '--print-module-deps',
  '--class-path', jars.join(process.platform === 'win32' ? ';' : ':'),
  join(target, 'classes'),
], { encoding: 'utf8' }).trim()
if (!detected) throw new Error('jdeps did not return a module set')

const modules = [...new Set([...detected.split(','), 'jdk.compiler', 'jdk.crypto.ec', 'jdk.zipfs'])].sort().join(',')
if (existsSync(runtime)) rmSync(runtime, { recursive: true, force: true })
run(tool('jlink'), [
  '--add-modules', modules,
  '--strip-debug',
  '--no-header-files',
  '--no-man-pages',
  '--compress=2',
  '--output', runtime,
])

console.log(`OPENGIS_JLINK_MODULES=${modules}`)
console.log(`OPENGIS_JLINK_RUNTIME=${runtime}`)
run(join(runtime, 'bin', javaCommand), ['-version'])

function run(command, args) {
  const isCommandScript = process.platform === 'win32' && command.toLowerCase().endsWith('.cmd')
  const executable = isCommandScript ? (process.env.ComSpec || 'cmd.exe') : command
  const executableArgs = isCommandScript ? ['/d', '/s', '/c', command, ...args] : args
  const result = spawnSync(executable, executableArgs, { cwd: root, stdio: 'inherit', shell: false })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${command} exited with status ${result.status}`)
}
