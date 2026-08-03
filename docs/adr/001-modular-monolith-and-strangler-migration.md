# ADR-001: Modular monolith and strangler migration

- Status: Accepted
- Date: 2026-08-02
- Decision owners: OpenGIS maintainers

## Context

OpenGIS is a local desktop system composed of Electron Main, React Renderer, a Python Sidecar, generated-code subprocesses and resident Workers. The Python Sidecar owns both control-plane capabilities and GIS computation. A direct file-by-file rewrite would combine protocol changes, data migration, Agent behavior changes and GIS numerical changes into one unreviewable release.

## Decision

Build the Java backend as a Maven modular monolith using the top-level modules documented in the migration plan: common, framework, platform, AI, knowledge, Agent, Tool, Workflow, GIS, Worker and Server. Keep the React/Electron UI as a separate Node project.

Use a strangler migration:

1. freeze protocol/data behavior;
2. introduce the Java Sidecar behind a backend launcher/feature flag;
3. move capabilities by bounded group;
4. compare read-only behavior and replay side effects in isolated workspaces;
5. make Java the default only after capability gates pass;
6. remove packaged Python after two stable Java-only release cycles.

`opengis-server` is the composition root. Lower modules must not depend on Server. Agent invokes tools only through ToolRuntime; GIS/Worker/Workflow contribute Tool implementations without Tool depending back on them.

## Consequences

Positive:

- each stage remains runnable and reversible;
- protocol and `.opengis` compatibility are testable independently;
- module boundaries make the Java project suitable for learning;
- GIS numerical risk is isolated from transport/control-plane work.

Negative:

- Python and Java coexist during migration;
- launch configuration and compatibility tests are temporarily more complex;
- the team must actively prevent common/framework/platform from becoming dumping grounds.

## Rejected alternatives

- Big-bang rewrite: unacceptable rollback and comparison risk.
- Microservices: unnecessary for a local desktop Sidecar and adds deployment/observability complexity.
- Single Maven module: easy initially but likely recreates the current oversized RPC/Agent coupling.
- Migrate UI simultaneously: produces excessive unrelated diff and weakens protocol isolation.

## Enforcement

- Maven Enforcer rejects dependency cycles and version drift.
- ArchUnit checks forbidden module/package dependencies.
- Phase 0 transcripts and fixtures are shared across Python, Java and TypeScript.
- Every capability cutover documents fallback behavior and data side effects.
