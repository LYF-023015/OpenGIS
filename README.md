<p align="center">
  <img src="resources/icons/app-icon.png" alt="OpenGIS" width="120" />
</p>

<h1 align="center">OpenGIS</h1>

<p align="center">
  <a href="README.zh.md">中文</a> |
  <strong>English</strong>
</p>

<p align="center">
  <strong>Java-powered, Agent-driven open-source GIS desktop app — geospatial analysis, cartography, automation & knowledge retention with natural language</strong>
</p>

<p align="center">
  <a href="#1-introduction">Introduction</a> •
  <a href="#2-feature-overview">Features</a> •
  <a href="#3-architecture">Architecture</a> •
  <a href="#4-getting-started">Getting Started</a> •
  <a href="#5-development-guide">Development</a> •
  <a href="#6-roadmap">Roadmap</a> •
  <a href="#7-license">License</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Windows-blue" alt="Platform" />
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License" />
  <img src="https://img.shields.io/badge/electron-30.x-47848F?logo=electron" alt="Electron" />
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1" />
  <img src="https://img.shields.io/badge/react-18.x-61DAFB?logo=react" alt="React" />
  <img src="https://img.shields.io/badge/maplibre-4.x-396CB2" alt="MapLibre" />
</p>

---

> [!NOTE]
> **Java Edition:** This repository is [LYF-023015/OpenGIS](https://github.com/LYF-023015/OpenGIS), a Java-backend evolution of the original [ATFfang/OpenGIS](https://github.com/ATFfang/OpenGIS). The production desktop app now runs a bundled Java 21 / Spring Boot sidecar; the former Python backend remains in the repository only as an explicit recovery and compatibility backup. The current validated release target is Windows.

### Java Edition quick start

Requirements: Windows, Node.js 18+, JDK 21, and Git. Maven is provided through the repository wrapper.

```powershell
npm install
java-backend\mvnw.cmd --batch-mode verify
npm run dev:electron
```

Build the Windows package with `npm run dist:win`. See [`java-backend/README.md`](java-backend/README.md) for the module layout and [`docs/migration/phase10/README.md`](docs/migration/phase10/README.md) for migration and release evidence.

<p align="center">
  <a href="https://youtu.be/F5lVRs_XXjU">
    <img src="https://img.youtube.com/vi/F5lVRs_XXjU/maxresdefault.jpg" alt="OpenGIS Demo" width="600">
    <br>
    <img src="https://img.shields.io/badge/▶_Watch_Demo-red?style=for-the-badge" alt="Watch Demo">
  </a>
</p>

## 1. Introduction

OpenGIS is an Agent-based open-source GIS desktop application. It is not a simple "map + chat box" — it brings **GIS data management, map rendering, spatial analysis, cartographic export, workflows, background workers, reusable operations, memory systems, and tool governance** together in a single desktop environment.

The project is under active development. The current goal is not to replace all capabilities of ArcGIS Pro / QGIS, but to explore a more natural way of working with GIS: users describe intent in natural language, and the Agent reads data, runs code, calls map capabilities, generates charts, accumulates scripts and Operations within a governed tool system — with results displayed directly on the map, in chat, dashboards, or the cartographic canvas.

<p align="center">
  <img src="resources/assets/0.png" alt="Main UI: Map + Chat + Multi-panel Layout" width="100%" />
</p>
<p align="center"><sub>Main UI: Left — Resources / Layers / Workflow / Operation / Worker panels · Center — Map / Code / Layout Composer · Right — Agent Chat.</sub></p>

<br>
<p align="center">
  <img src="resources/assets/用例1.png" alt="Use Case 1" width="100%" />
</p>

<br>
<p align="center">
  <img src="resources/assets/用例2.png" alt="Use Case 2" width="100%" />
</p>

<br>
<p align="center">
  <img src="resources/assets/1.png" alt="Agent Chat: Streaming Code, Tool Calls, Chart Output" width="100%" />
</p>
<p align="center"><sub>The Agent can read data, execute controlled Java code, generate charts, and display images / map results back in the chat and on the map.</sub></p>

<br>
<p align="center">
  <img src="resources/assets/2.png" alt="GIS Data Loading with Multiple Renderers" width="100%" />
</p>
<p align="center"><sub>Supports vector, raster, categorized styling, graduated styling, style variables, layer ordering, map camera control, and more.</sub></p>

<br>
<p align="center">
  <img src="resources/assets/3.png" alt="Workflow DAG Editor" width="100%" />
</p>
<p align="center"><sub>Workflows organize multi-step tasks as DAGs, with structured input/output descriptions passed between nodes.</sub></p>

## 2. Feature Overview

### 2.1 Agent Capabilities

- **Function-call Agent Loop**: Structured tool calls as the primary path, avoiding the old CodeAct era of guessing code blocks and tool calls from plain text.
- **Code Execution**: Controlled Java code is statically checked, compiled, and run in an isolated child JVM for ad-hoc GIS analysis, data cleaning, and long-tail algorithm validation.
- **Tool Governance**: All tools go through unified schema, permissions, result normalization, event archiving, and frontend display.
- **Plan / Sub-Agent / Workflow**: Plan, Subagent, and Workflow all share the same session / run / MessagePart protocol.
- **Memory & Knowledge Retention**: Structured MemoryStore, ContextProjector, KnowledgeExtractor, and FailureMemory jointly manage context and experience.
- **Operation Reuse**: A complex analysis can be distilled into an editable, verifiable, runnable, workspace-shareable Operation.
- **Worker Background Tasks**: With user approval, the Agent can create / restart / pause / delete resident Java Workers running in isolated child JVMs for dynamic data ingestion and real-time map rendering.

### 2.2 GIS & Map Capabilities

- **Vector Data**: GeoJSON, CSV, Shapefile, KML, GeoPackage, and other common formats.
- **Raster Data**: GeoTIFF / TIFF parsing, hybrid frontend/backend rendering, server-side tiles, color ramps, and transparency control.
- **Map Rendering**: MapLibre GL JS with point / line / polygon, categorized styling, graduated styling, size variables, opacity variables, ordering variables, labels, filters, and highlights.
- **Dynamic Maps**: Workers continuously push `rpc.ui.map.dynamic_layer_update` via stdout JSON protocol for real-time layer updates.
- **3D View**: Map pitch / bearing control with basic extrusion styles.
- **Cartographic Canvas**: Layout Composer supports map frames, scale bars, north arrows, legends, canvas ratios, image export, and is designed for ArcGIS / QGIS-style extensibility.
- **Data Pivot**: Layers / files can open a data pivot panel with tables, statistics, field distributions, and Agent analysis results displayed separately.

### 2.3 Automation & Extensibility

- **Workflow**: DAG-based multi-step analysis where nodes describe what they receive from upstream and what they output.
- **Operation**: Software-level atomic operations with input/output schemas, dependencies, code, documentation, and run history.
- **Worker**: Resident Java worker processes for real-time data, API polling, and dynamic rendering.
- **Project Skills**: Skills are project-level capability / knowledge packages, distinct from tools. Tools are functions the Agent can call directly; Skills are user-injected context, flows, constraints, or capability sets.
- **Run Archive**: Every Agent execution round is archived as an event stream of tool calls, MessageParts, artifacts, and metadata.

## 3. Architecture

### 3.1 Process Model

OpenGIS uses an **Electron shell + React Renderer + Spring Boot Java backend** architecture:

```text
Electron Main
  ├─ Window, menu, file system, settings, Java backend lifecycle
  │
  └─ Renderer (React + TypeScript)
       ├─ MapLibre map rendering
       ├─ Chat / MessagePart UI
       ├─ Layers, assets, Operation, Workflow, Worker, Layout Composer
       └─ JSON-RPC Dispatcher: handles Java -> UI reverse RPC

Java Backend (Spring Boot + Spring AI)
  ├─ WebSocket JSON-RPC service
  ├─ Agent loop / session / memory / tool runtime
  ├─ GIS / OSM / datasource / raster / operation / workflow / worker integration
  ├─ PluginRuntime: dependency-ordered built-in capability assembly
  └─ Java Worker processes
```

| Layer | Technology | Responsibilities | Key Directories |
|---|---|---|---|
| Electron Main | Electron 30 + Node | Window, menu, IPC, Java backend lifecycle | `electron/` |
| Renderer | React 18 + TypeScript + Zustand | UI, map, layer state, reverse RPC handlers | `src/app/`, `src/shell/`, `src/plugins/`, `src/shared/` |
| Map Engine | MapLibre GL JS | WebGL map, source/layer sync, export | `src/plugins/gis/map/` |
| Java Backend | Java 21 + Spring Boot + Spring AI | JSON-RPC, Agent, Tool, Workflow, GIS, Worker | `java-backend/` |
| Java Plugin Host | Plain Java `PluginRuntime` | Tool catalogs, memory, GIS and execution capability assembly | `java-backend/server/src/org/opengis/framework/`, `java-backend/server/src/org/opengis/plugins/` |
| Renderer Plugin Host | TypeScript | RPC bridge, map extensions and UI contributions | `src/app/plugins/` |
| Java Worker | Java process | Background dynamic data, continuous rendering | `java-backend/server/src/org/opengis/worker/` |

The plugin boundary follows **stable kernel + domain modules + capability plugins**. The kernel only owns lifecycle, dependency ordering, profiles and service lookup; domain modules implement Agent, Tool, GIS and Workflow behavior; capability plugins register cross-module adapters at the composition boundary. Plugins are compiled into the application—not hot-loaded scripts or a marketplace. See [`docs/architecture/plugin-architecture.md`](docs/architecture/plugin-architecture.md).

### 3.2 Communication: Bidirectional JSON-RPC

The Renderer and Java backend communicate over a single WebSocket channel using JSON-RPC 2.0, supporting both directions:

```text
Renderer -> Java
  chat.user_message
  rpc.code.run_script
  rpc.runs.list / get
  rpc.agent.*
  rpc.worker.*

Java -> Renderer
  rpc.ui.map.add_layer_from_geojson
  rpc.ui.map.dynamic_layer_update
  rpc.ui.map.set_layer_style
  rpc.ui.ask.*
  chat / event notification
```

The frontend `src/shared/backend/backendClient.ts` handles WebSocket connection, request timeouts, notification dispatch, and dynamic map event buffering. The `rpc-bridge` plugin registers inbound `rpc.ui.*` notifications with `src/shared/backend/rpc/handlers/`, which ultimately write to Zustand stores or invoke MapEngine.

Key principles:

- **Map state lives in the frontend**: Java does not hold MapLibre handles; all layer state is authoritative in the frontend store.
- **Domain computation runs in Java**: Spatial analysis, raster processing, model inference, Operations, Workflows, and Workers are implemented by Java modules.
- **UI operations use reverse RPC**: Java tools command the frontend to load layers, update styles, and switch views through `rpc.ui.map.*`.
- **Dynamic data uses notifications**: Workers emit structured events that the sidecar forwards to the frontend dynamic handler.

### 3.3 Agent Architecture

The Agent has been upgraded from the old CodeAct to a mainstream function-call architecture with the following layered design:

```text
AgentProfile
  -> SessionCoordinator
  -> SpringAiAgentRunner / ChatClient
  -> RunLifecycleAdvisor
  -> ToolCallingAdvisor / OpenGisToolCallingManager
  -> ToolRuntime / PermissionRuntime
  -> EventLog / RunArchive / MessagePart
```

| Domain package | Responsibilities | Directory |
|---|---|---|
| `agent` | Spring AI Agent runner, SessionCoordinator, RuntimeControl, context and archives | `java-backend/server/src/org/opengis/agent/` |
| `tool` | ToolRuntime, schema validation, permission and artifact handling | `java-backend/server/src/org/opengis/tool/` |
| `ai` | Spring AI model configuration and provider projection | `java-backend/server/src/org/opengis/ai/` |
| `workflow` | Workflow model, storage, output passing and DAG orchestration | `java-backend/server/src/org/opengis/workflow/` |

#### 3.3.1 Function-call First

OpenGIS now uses function calls as the primary Agent path. The model outputs structured tool calls, and the framework executes tools by schema and returns structured results. Code execution remains a governed tool but is no longer the loop control protocol.

This addresses several issues from the old CodeAct:

- Agent reply text is never mistaken for executable code.
- Tool parameters are constrained by schema, not natural language parsing.
- The frontend can uniformly display tools, code, artifacts, Operations, Workers, and errors.
- Permission approval, tool pruning, result compression, and run archiving all have unified entry points.

#### 3.3.2 MessagePart Display Protocol

Agent output is no longer a single mixed block of text, but event-driven `MessagePart[]`:

| Part Type | Purpose |
|---|---|
| `text` | Normal Agent reply |
| `tool` | Tool call and result |
| `code` | Generated / executed Java code (legacy Python records remain replayable) |
| `artifact` | Images, reports, files, layers, etc. |
| `operation` | Operation run block |
| `progress` | Current bottom status bar |
| `plan` | Plan / Workflow progress |
| `error` | Errors and interruptions |

The frontend Chat directly renders MessageParts. Default tool calls are collapsed; code blocks are collapsible; execution output is compressed by default; images and locally-referenced Markdown resources are rendered through secure path conversion.

#### 3.3.3 Loop Convergence & Anomaly Protection

The Agent loop does not rely on hard-coded step limits. Instead, it converges based on:

- The model naturally stops with no tool calls.
- TurnObjective / DeviationGuard detects goal deviation.
- Loop Anomaly Detector catches repeated tools, repeated failures, and invalid retries.
- RuntimeControl performs health checks and graceful shutdown for long-running tasks like Workers and Operations.
- ToolRuntime provides structured feedback on errors, encouraging fixes to the original file / Operation rather than circumventing it.

### 3.4 Boundaries: Tool, Skill, Operation

Current naming conventions:

| Name | Definition | Example | Lifecycle |
|---|---|---|---|
| **Tool** | A function the Agent can call directly, with schema, permissions, and result structure | `read_file`, `edit_file`, `add_layer`, `start_worker` | Built into code |
| **Skill** | A user / project injectable knowledge, flow, constraint, or capability package that can influence context and tool selection | External skill packages, project-level docs | Project / user level |
| **Operation** | A reusable software-level atomic operation with input/output schemas, dependencies, main program, docs, and run history | DBSCAN clustering, KDE, format conversion | Built-in + workspace |
| **Workflow** | DAG multi-step task orchestration where nodes define input/output descriptions | Academic reports, watershed analysis | `.flow.json` |
| **Worker** | A resident Java service in an isolated child JVM for continuous data processing and dynamic rendering | Flight tracking, dynamic points, real-time trajectories | Workspace |

Legacy code sometimes called tools "skills." In the new architecture, avoid mixing: **Tools are tools, external capability packages are Skills, reusable algorithms are Operations.**

### 3.5 Tool Runtime

Tools are centralized in `java-backend/server/src/org/opengis/tool/`, with GIS tools in `java-backend/server/src/org/opengis/gis/`:

| Tool Group | Representative Tools | Description |
|---|---|---|
| File | `read_file`, `write_file`, `edit_file`, `list_directory`, `glob`, `grep` | Fuzzy matching, diff output, read protection, file suggestions |
| Shell | `bash` | Shell execution with permission governance and risk prompts |
| Web | `webfetch`, `websearch` | Fetch web pages, real-time search |
| Map | `list_layers`, `add_layer`, `update_layer_style`, `set_categorized_style`, `set_graduated_style`, `set_layer_order` | Operate frontend maps via reverse RPC |
| Raster | `add_raster`, `get_raster_info`, `set_raster_style` | TIFF / GeoTIFF / server-side tiles / color ramps |
| OSM | `osm_call` | Nominatim + Overpass for OSM data |
| DataSource | `datasource_call` | Built-in data sources |
| Workflow | `create_workflow`, `run_workflow`, etc. | Create, read, save, execute Workflows |
| Operation | `list_operations`, `run_operation`, `edit_operation`, `validate_operation` | Run and maintain reusable operations |
| Worker | `start_worker`, `start_dynamic_map_worker`, `restart_worker`, `wait_worker_update`, `pause_worker`, `delete_worker` | Resident background services |
| Report | `export_map_snapshot`, `write_report_section`, `export_report_pdf` | Reports and export |
| Subagent | `run_subagent` | Sub-agent execution |
| Debug | `debug_agent_context` | Context and tool exposure debugging |

ToolRuntime is responsible for:

- Registering `OpenGisTool` implementations as LLM-visible JSON schemas.
- Performing parameter validation and permission decisions before execution.
- Normalizing output, truncating large results, and generating artifact pointers after execution.
- Writing tool calls / results to RunArchive and MessagePart.
- Projecting frontend UI events into a unified stream.

### 3.6 Memory & Context

The new memory system is no longer centered on injecting a single `memory.md`, but is organized in layers:

```text
MemoryStore
  facts          # Stable facts: projects, files, layers, user preferences
  recipes        # Reusable operation steps and debugging experience
  dataset_cards  # Dataset field, range, statistical summary, path
  failure_memory # Repeated errors, failure causes, fix suggestions

ContextProjector
  -> Retrieves relevant memory based on current user intent
  -> Combines recent turns / working state / artifacts
  -> ProviderProjector generates the complete provider request
```

Key points:

- **Task-based retrieval**: Not all history is stuffed into the system prompt; relevant facts are selected per turn.
- **Complete request budget**: Context trimming considers not just message history, but also system prompt, tools, memory, artifacts, and provider format.
- **Failure memory**: Consecutive tool failures, missing packages, and signature misuse accumulate as failure memory to avoid repeating mistakes.
- **Knowledge extraction**: After a run, KnowledgeExtractor extracts facts, recipes, and dataset cards.
- **User instructions**: Global preferences and project-level preferences are managed separately.

### 3.7 RunArchive & Event-Sourced UI

Every Agent run writes to `.opengis/runs/<run_id>/`:

```text
meta.json
events.jsonl
message_parts.jsonl
tool_calls.jsonl
artifacts.jsonl
steps.jsonl
```

This event-sourced structure enables the frontend to:

- Render Chat in real time.
- Recover historical runs.
- Display tool calls, code, Operations, Workers, and Artifacts.
- Detect abnormal endings and backfill running status.
- Offload long outputs to artifact pointers to prevent UI and context from freezing on large JSON.

### 3.8 Workflow Architecture

Workflow is DAG orchestration, not simple prompt templates. Each node needs to describe:

- What it receives from upstream.
- What task it executes.
- What it outputs to downstream.
- Whether to retry on failure.

Workflow files are typically stored as `.flow.json`. Built-in workflows are located in project resources or the workspace's `.opengis/workflows/`. Users can also have the Agent create and save workflows.

Execution flow:

```text
Workflow document
  -> parse nodes / edges
  -> topological sort
  -> node session
  -> node output summary + artifact
  -> downstream context
  -> final report
```

The frontend Workflow UI and Plan UI share the MessagePart protocol but display differently: Workflow emphasizes DAG progress, Plan emphasizes current task steps.

### 3.9 Operation Architecture

Operation is a more stable reuse unit than scripts. It distills a complex exploration into a reusable, modifiable, verifiable atomic capability.

A workspace Operation contains:

```text
operation/
  operation.json     # Name, description, input/output schema, dependencies, version
  src/main/java/...  # Single OpenGisScript entry point
  README.md          # Usage instructions
  revisions/         # Auditable historical versions
```

Two types of Operations:

- **Built-in Operations**: Shipped with OpenGIS source, shared across all workspaces.
- **Workspace Operations**: Created in user projects, can be promoted to built-in.

The Agent can:

- Query Operations.
- Construct parameters by schema and run.
- Read Operation code and fix after a failed run.
- Promote stable scripts to Operations.

The frontend Operation panel uses a list + detail structure, and Chat also displays run status and results as independent Operation blocks.

### 3.10 Worker Architecture

Worker is a resident Java service running in an isolated child JVM, suitable for:

- Continuously polling external APIs.
- Real-time reading of dynamic data.
- Continuous data stream processing.
- Pushing dynamic points, trajectories, and status to the map.

Worker service package structure:

```text
worker/<name>-<worker_id>/
  manifest.json        # Runtime, entry class, dependencies and permissions
  config.json          # Interval, layer ids and API parameters
  metadata.json        # State, restart count and runtime information
  README.md
  stdout.log
  stderr.log
  src/main/java/.../Worker.java
```

The entry class implements `OpenGisWorker` and publishes ordered full / diff map events through the SDK:

```java
public final class FlightWorker implements OpenGisWorker {
  @Override
  public void run(WorkerContext context) throws Exception {
    context.publish("rpc.ui.map.dynamic_layer_update", frame);
  }
}
```

The worker manager handles compilation, the child JVM, event forwarding, bounded restart, resource sampling, and process-tree cleanup. The frontend dynamic handler updates the layer store and instantly syncs MapLibre sources.

Constraints:

- Default max of two running workers.
- Start, restart, pause, and delete require permission governance.
- Background continuous tasks must use workers, not `execute_code`.
- The entry class must implement `OpenGisWorker`; legacy Python Workers are read-only and produce a migration report rather than executing in Java mode.

### 3.11 Map & Rendering Architecture

The frontend map is managed by `src/plugins/gis/map/engine/MapEngine.ts`. Renderers are split across `src/plugins/gis/map/renderers/`:

| Renderer | Purpose |
|---|---|
| `circleRenderer` | Points / multi-points |
| `lineRenderer` | Lines / multi-lines |
| `fillRenderer` | Polygons |
| `categorizedRenderer` | Categorized coloring |
| `graduatedRenderer` | Numeric graduated |
| `symbolRenderer` | Icons / labels |
| `rasterRenderer` | Raster |
| `extrusionRenderer` | 3D extrusion |

Layer data parsing is in `src/shared/geo/parsers/`. Large vector data uses handle-based strategies to avoid repeatedly stuffing entire GeoJSON into the React store. Raster data supports both frontend parsing and backend server-side tile paths.

The style system supports:

- Point color, size, opacity, stroke.
- Line color, width, opacity, dashes.
- Fill, border, separate fill and border opacity.
- Categorized and graduated coloring, explicit color mapping.
- Size variables, opacity variables, ordering variables.
- Labels / symbols / legends / filters.

### 3.12 Raster Architecture

Raster loading uses a hybrid frontend/backend strategy:

- **Frontend lightweight parsing**: Suitable for small GeoTIFFs, using `geotiff.js` to read and render as image sources.
- **Backend RasterService**: Suitable for larger TIFF / multi-band data; reads statistics, generates tiles, applies color ramps.
- **Style editing**: Supports ramp, custom stops, source value stops, transparency, and stretch min/max.
- **Agent awareness**: `get_raster_info` lets the Agent read dimensions, bands, statistics, and color ramp state.

Mainstream WebGIS typically renders server-side rasters as PNG / JPEG tiles or Cloud Optimized GeoTIFF window reads. OpenGIS currently uses PNG tiles and image sources, with potential future enhancement for COG / pyramids / overviews.

### 3.13 Layout Composer

Layout Composer is a canvas system for cartographic export, targeting the basic mapping workflow of ArcGIS / QGIS:

- Define canvas ratios: 16:9, 4:3, 1:1, custom.
- Add map frames with adjustable position, size, and internal viewport.
- Add scale bars, north arrows, legends.
- Adjust background, borders, fonts, colors, transparency.
- Export images.
- Expose canvas elements as Agent tools for natural-language cartography.

Current implementation is in `src/plugins/gis/layout/`.

### 3.14 Permissions & Security

OpenGIS is not a hard sandbox product, but provides multiple safety layers:

| Layer | Mechanism |
|---|---|
| WebSocket | Local loopback + startup token |
| ToolRuntime | Tool permission policy: ask / allow / deny |
| Shell / File | Risk action logging, approval UI, read-before-write protection |
| Worker | Start / restart / delete approval, max running limit |
| RunArchive | Complete event and tool call audit trail |
| Workspace | Git snapshot, rollback capable |
| Java Code Execution | Isolated child JVM, interruptible, process tree cleanup |

### 3.15 Project Directory

```text
OpenGIS/
  electron/                         # Electron main / preload
  src/
    app/                            # React startup and plugin runtime
    shell/                          # Desktop workbench shell
    plugins/
      assistant/                    # Chat and approval
      workspace/                    # Assets, viewers and Java scripts
      gis/                          # Map, layers, analysis, layout and operations
      automation/                   # Workflows, workers and runs
      system/                       # Settings and tool/skill catalog
    shared/                         # JSON-RPC, geo contracts and shared UI
  java-backend/
    server/src/org/opengis/         # One Spring Boot modular monolith
      core/ assistant/ gis/ automation/ tool/ server/
    script-sdk/                     # Stable API for isolated Java scripts and workers
  python-backend/                   # Explicit dev fallback / migration reference; excluded from production packages
  resources/                        # Icons, screenshots, static assets
  docs/                             # Design records and bug scans
```

## 4. Getting Started

### 4.1 Prerequisites

| Dependency | Version | Required | Description |
|---|---|---|---|
| Node.js | >= 18 | Yes | Frontend, Electron, build |
| Java | 21 | Yes | Spring Boot backend, Agent, GIS and workers |
| Git | Any | Yes | Workspace snapshot / run rollback |
| LLM API Key | OpenAI / Anthropic / DeepSeek / MiniMax / GLM / Ollama, etc. | Required for Agent | Basic map features do not require LLM |
| GDAL-compatible native libraries | Platform dependent | Optional | Additional raster/vector format capabilities |

### 4.2 Clone the Repository

```bash
git clone <repo-url>
cd OpenGIS
```

### 4.3 Install Frontend Dependencies

```bash
npm install
```

### 4.4 Verify the Java Backend

```powershell
cd java-backend
./mvnw.cmd verify
```

The Maven Wrapper runs the full Java module test suite with the repository's pinned Maven configuration. Production packaging uses `npm run build:java-runtime` to create the bundled Java runtime; a separate Maven installation is normally unnecessary.

### 4.5 Start Development Mode

```bash
npm run dev:electron
```

Startup sequence:

1. electron-vite starts the renderer dev server.
2. Compiles Electron main / preload.
3. Electron main launches the Java backend.
4. Renderer connects to the backend WebSocket via token.

Once the window and Java backend ready status appear, you're good to go.

### 4.6 Configure the Model

Open Settings / Model:

| Field | Description |
|---|---|
| Provider | OpenAI, Anthropic, DeepSeek, MiniMax, GLM, Ollama, etc. |
| Protocol | OpenAI Compatible or Anthropic Compatible |
| Base URL | Model service endpoint |
| API Key | Saved locally |
| Model Name | Specific model ID |

Click Test Connection to verify. After saving, send "hello" to the Agent; seeing a streaming reply means the pipeline is working.

### 4.7 Open a Workspace

Use File / Open Workspace to select a project directory. OpenGIS creates `.opengis/` under the workspace:

```text
.opengis/
  runs/
  workflows/
  operations/
  contexts/
  memory/
  permissions.json
  map-layers.json
```

Different workspaces have independent layers, runs, Operations, workflows, and memory.

## 5. Development Guide

### 5.1 Common Commands

```bash
npm run dev:electron     # Start desktop dev mode
npm run typecheck        # TypeScript type checking
npm test                 # Frontend unit tests
npm run build            # Build main / preload / renderer
npm run dist:mac         # macOS packaging
npm run dist:win         # Windows packaging
npm run dist:linux       # Linux packaging
```

Backend tests:

```powershell
cd java-backend
./mvnw.cmd verify
```

### 5.2 Adding a Tool

1. Implement `OpenGisTool` in `java-backend/server/src/org/opengis/tool/` (or `java-backend/server/src/org/opengis/gis/` for GIS tools).
2. Declare its `ToolDefinition`, JSON schema, group and risk level.
3. Access workspace, cancellation and UI RPC via `ToolExecutionContext`.
4. Contribute it from the owning `ToolCatalogPlugin`; place cross-domain adapters in `java-backend/server/src/org/opengis/plugins/`.
5. For map operations, prefer sending `rpc.ui.map.*` reverse RPC; do not maintain map state in the Java backend.
6. Add tests covering parameter validation, permission, return structure, and contribution rollback.

### 5.3 Adding Map Capabilities

Map capabilities typically require changes on both sides:

1. Backend tool: Declare the Agent-callable entry point.
2. Frontend RPC handler: Receive `rpc.ui.map.*` in `src/shared/backend/rpc/handlers/map/`.
3. Store: Extend `MapLayerDefinition` or `LayerStyle` if needed.
4. Renderer: Extend MapLibre paint / layout in `src/plugins/gis/map/renderers/`.
5. UI: If users need manual control, add an edit entry in the layer or style panel.

### 5.4 Adding an Operation

Built-in Operations go in:

```text
java-backend/server/src/org/opengis/gis/operation/builtin/
```

Recommended structure:

```text
MyOperation.java
```

The Operation definition should clearly describe:

- Input schema.
- Output schema.
- Java/runtime dependencies.
- Operation description.
- Applicable scenarios.

### 5.5 Adding Worker Scenarios

Workers should not be written as a single large class. Split a Java package by responsibility:

```text
Worker.java          # Implements OpenGisWorker and owns lifecycle
Datasource.java      # Fetch data
WorkerService.java   # Update state, trajectories and filters
Publisher.java       # Publish full / diff dynamic-map events
```

Dynamic maps must ensure:

- Stable layer IDs.
- Full first frame, subsequent diffs, or use high-level helpers for automatic handling.
- Stable feature IDs.
- Monotonically increasing sequence.
- Do not write infinite loops in `execute_code`; continuous jobs must use Workers.

### 5.6 Windows Notes

- Paths may contain spaces and Chinese characters; use `Path` / JSON parameters, not shell string concatenation.
- Development JDK and bundled jlink runtime paths differ; do not hardcode a local JDK path.
- Java code and Workers run in child JVMs; every new background process must integrate cancellation and process-tree cleanup.
- Native GIS libraries are optional enhancements; the Java modules provide the baseline GeoJSON, OSM, JTS, and GeoTIFF capabilities.

## 6. Roadmap

- More complete dynamic map protocol: frontend source diff adapter, state monitoring, Worker debug loop.
- Stronger Operation marketplace: versioning, dependency locking, visual parameter forms, run benchmarks.
- More complete Layout Composer: legend grouping, map bookmarks, multi-page, PDF export.
- Larger data rendering: vector tiles, WebGL aggregation, deck.gl / custom high-performance rendering evaluation.
- Stronger raster pyramids: COG, overviews, server-side window reads, time-series rasters.
- More mature skills integration: project-level skill registry, skill marketplace, permission isolation.
- Stricter permission modes: configurable approval, persistent authorization, risk rule templates.

## 7. License

This project is licensed under the **MIT License**.

---

OpenGIS is under active development. This README describes the current mainline architecture, core implementation paths, and near-term evolution direction. If there are minor discrepancies between code and documentation, the code and tests are authoritative.
