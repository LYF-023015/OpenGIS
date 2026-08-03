param(
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$moduleRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaBackendRoot = (Resolve-Path (Join-Path $moduleRoot '..')).Path
$targetRoot = Join-Path $moduleRoot 'target'
$runtimePath = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    Join-Path $targetRoot 'runtime'
}

$resolvedTarget = [IO.Path]::GetFullPath($targetRoot)
if ($runtimePath -eq $resolvedTarget -or -not $runtimePath.StartsWith($resolvedTarget + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Runtime output must be a child of opengis-server/target: $runtimePath"
}

$maven = Join-Path $javaBackendRoot 'mvnw.cmd'
$parentPom = Join-Path $javaBackendRoot 'pom.xml'
if (-not (Test-Path -LiteralPath $maven)) {
    throw "Maven Wrapper is missing: $maven"
}

# The second Maven invocation resolves the internal modules from the local
# repository, so install the reactor artifacts before copying runtime jars.
& $maven -q -f $parentPom -pl opengis-server -am install -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Maven reactor install failed.' }
& $maven -q -f $parentPom -pl opengis-server dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/jlink-libs'
if ($LASTEXITCODE -ne 0) { throw 'Maven dependency copy failed.' }

$javaHome = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName((Get-Command java).Source))
$jdeps = Join-Path $javaHome 'bin\jdeps.exe'
$jlink = Join-Path $javaHome 'bin\jlink.exe'
$dependencyJars = @(Get-ChildItem -LiteralPath (Join-Path $targetRoot 'jlink-libs') -Filter '*.jar')

$classPath = ($dependencyJars.FullName -join [IO.Path]::PathSeparator)
$classesDirectory = Join-Path $targetRoot 'classes'
# Analyze application classes against the copied dependency classpath. Using
# the repackaged/original JAR directly triggers a JDK 21 jdeps parser failure
# on one of Spring Boot 4's multi-release dependencies.
$detected = (& $jdeps --multi-release base --ignore-missing-deps --recursive --print-module-deps --class-path $classPath $classesDirectory).Trim()
if ($LASTEXITCODE -ne 0 -or -not $detected) { throw 'jdeps could not determine runtime modules.' }
# jdeps sees the java.compiler API but cannot infer the ToolProvider implementation
# loaded at runtime by JavaCompiler. Phase 8 requires jdk.compiler explicitly.
$modules = (($detected -split ',') + @('jdk.compiler', 'jdk.crypto.ec', 'jdk.zipfs') | Sort-Object -Unique) -join ','

if (Test-Path -LiteralPath $runtimePath) {
    Remove-Item -LiteralPath $runtimePath -Recurse -Force
}
& $jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtimePath
if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }

Write-Output "OPENGIS_JLINK_MODULES=$modules"
Write-Output "OPENGIS_JLINK_RUNTIME=$runtimePath"
& (Join-Path $runtimePath 'bin\java.exe') -version
