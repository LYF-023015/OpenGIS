/** Workflow schema v2 shared by the Java executor and the visual editor. */
export const WORKFLOW_SCHEMA_VERSION = 2 as const

export type WorkflowNodeType =
  | 'agent_task'
  | 'tool_call'
  | 'operation'
  | 'java_script'
  | 'subworkflow'

export interface NodePosition { x: number; y: number }
export interface NodePort { name: string; label?: string; type?: string; description?: string }

export interface ExecutionReference {
  kind: WorkflowNodeType
  /** Agent profile, tool name, operation id, workspace-relative .java file, or workflow id. */
  ref: string
}

export type JsonLogicExpression =
  | null | boolean | number | string
  | JsonLogicExpression[]
  | { [operator: string]: JsonLogicExpression }

export interface WorkflowCondition {
  /** Safe JSON-logic subset; never JavaScript, Python, SpEL, or source code. */
  expression: JsonLogicExpression
  description?: string
  onFalse?: 'fail' | 'retry' | 'skip'
}

export interface WorkflowNode {
  id: string
  title: string
  description?: string
  type: WorkflowNodeType
  execution: ExecutionReference
  inputContract?: string
  outputContract?: string
  inputs: NodePort[]
  outputs: NodePort[]
  params: Record<string, unknown>
  position: NodePosition
  conditions?: WorkflowCondition[]
  retryPolicy?: { maxAttempts: number; backoffMs: number }
  notes?: string
}

export interface WorkflowEdge {
  id: string
  source: string
  sourceHandle: string
  target: string
  targetHandle: string
  label?: string
}

export interface Workflow {
  schemaVersion: typeof WORKFLOW_SCHEMA_VERSION
  id: string
  name: string
  description?: string
  updatedAt: string
  createdAt: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  viewport?: { x: number; y: number; zoom: number }
  metadata?: Record<string, unknown>
}

export function createEmptyWorkflow(name: string): Workflow {
  const now = new Date().toISOString()
  return {
    schemaVersion: WORKFLOW_SCHEMA_VERSION,
    id: safeId(name),
    name,
    description: '',
    createdAt: now,
    updatedAt: now,
    nodes: [],
    edges: [],
    metadata: {},
  }
}

export function parseWorkflow(raw: string, fallbackName = 'Untitled'): Workflow {
  let obj: any
  try { obj = JSON.parse(raw) } catch (error) {
    throw new Error(`Invalid workflow JSON: ${(error as Error).message}`)
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    throw new Error('Workflow file is not a JSON object')
  }
  const version = Number(obj.schemaVersion ?? obj.schema_version ?? 1)
  if (version !== WORKFLOW_SCHEMA_VERSION) {
    throw new Error(`Workflow schema v${version} must be inspected and converted to v2 before editing.`)
  }
  const now = new Date().toISOString()
  const workflow: Workflow = {
    schemaVersion: WORKFLOW_SCHEMA_VERSION,
    id: typeof obj.id === 'string' && obj.id ? obj.id : safeId(obj.name || fallbackName),
    name: typeof obj.name === 'string' ? obj.name : fallbackName,
    description: typeof obj.description === 'string' ? obj.description : '',
    createdAt: typeof obj.createdAt === 'string' ? obj.createdAt : now,
    updatedAt: typeof obj.updatedAt === 'string' ? obj.updatedAt : now,
    nodes: Array.isArray(obj.nodes) ? obj.nodes.map(normaliseNode) : [],
    edges: Array.isArray(obj.edges) ? obj.edges.map(normaliseEdge) : [],
    viewport: obj.viewport && typeof obj.viewport === 'object'
      ? { x: Number(obj.viewport.x) || 0, y: Number(obj.viewport.y) || 0, zoom: Number(obj.viewport.zoom) || 1 }
      : undefined,
    metadata: obj.metadata && typeof obj.metadata === 'object' ? obj.metadata : {},
  }
  validateWorkflow(workflow)
  return workflow
}

function normaliseNode(node: any): WorkflowNode {
  const type = isNodeType(node?.type) ? node.type : 'agent_task'
  return {
    id: String(node?.id ?? ''),
    title: String(node?.title ?? 'Untitled Node'),
    description: typeof node?.description === 'string' ? node.description : '',
    type,
    execution: {
      kind: isNodeType(node?.execution?.kind) ? node.execution.kind : type,
      ref: String(node?.execution?.ref ?? (type === 'agent_task' ? 'gis-build' : '')),
    },
    inputContract: typeof node?.inputContract === 'string' ? node.inputContract : '',
    outputContract: typeof node?.outputContract === 'string' ? node.outputContract : '',
    inputs: Array.isArray(node?.inputs) ? node.inputs.map(normalisePort) : [],
    outputs: Array.isArray(node?.outputs) ? node.outputs.map(normalisePort) : [],
    params: node?.params && typeof node.params === 'object' ? node.params : {},
    position: { x: Number(node?.position?.x) || 0, y: Number(node?.position?.y) || 0 },
    conditions: Array.isArray(node?.conditions) ? node.conditions.map(normaliseCondition) : [],
    retryPolicy: {
      maxAttempts: Math.max(1, Math.min(Number(node?.retryPolicy?.maxAttempts) || 1, 10)),
      backoffMs: Math.max(0, Math.min(Number(node?.retryPolicy?.backoffMs) || 0, 60_000)),
    },
    notes: typeof node?.notes === 'string' ? node.notes : '',
  }
}

function normalisePort(port: any): NodePort {
  return { name: String(port?.name ?? ''), label: stringOrUndefined(port?.label),
    type: stringOrUndefined(port?.type), description: stringOrUndefined(port?.description) }
}

function normaliseCondition(condition: any): WorkflowCondition {
  return {
    expression: condition?.expression ?? false,
    description: stringOrUndefined(condition?.description),
    onFalse: ['fail', 'retry', 'skip'].includes(condition?.onFalse) ? condition.onFalse : 'fail',
  }
}

function normaliseEdge(edge: any): WorkflowEdge {
  return {
    id: String(edge?.id ?? `edge_${String(edge?.source)}_${String(edge?.target)}`),
    source: String(edge?.source ?? ''), sourceHandle: String(edge?.sourceHandle ?? ''),
    target: String(edge?.target ?? ''), targetHandle: String(edge?.targetHandle ?? ''),
    label: stringOrUndefined(edge?.label),
  }
}

export function validateWorkflow(workflow: Workflow): void {
  const ids = new Set<string>()
  for (const node of workflow.nodes) {
    if (!/^[A-Za-z0-9._-]+$/.test(node.id) || ids.has(node.id)) throw new Error(`Invalid or duplicate node id: ${node.id}`)
    ids.add(node.id)
    if (node.type !== node.execution.kind || !node.execution.ref.trim()) throw new Error(`Invalid execution reference for node ${node.id}`)
    if (node.execution.ref.toLowerCase().endsWith('.py')) throw new Error(`Python reference is not allowed in Workflow v2: ${node.id}`)
    if (node.type === 'java_script' && !node.execution.ref.toLowerCase().endsWith('.java')) throw new Error(`java_script must reference a .java file: ${node.id}`)
  }
  for (const edge of workflow.edges) {
    if (!ids.has(edge.source) || !ids.has(edge.target)) throw new Error(`Edge references a missing node: ${edge.id}`)
  }
}

export function serialiseWorkflow(workflow: Workflow): string {
  const clean = { ...workflow, schemaVersion: WORKFLOW_SCHEMA_VERSION, updatedAt: new Date().toISOString() }
  validateWorkflow(clean)
  return JSON.stringify(clean, null, 2)
}

function isNodeType(value: unknown): value is WorkflowNodeType {
  return ['agent_task', 'tool_call', 'operation', 'java_script', 'subworkflow'].includes(String(value))
}
function stringOrUndefined(value: unknown): string | undefined { return typeof value === 'string' ? value : undefined }
function safeId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'workflow'
}

export const WORKFLOW_DIR_NAME = 'workflows'
export const WORKFLOW_FILE_EXT = '.flow.json'
export function isWorkflowFilename(name: string): boolean { return name.toLowerCase().endsWith(WORKFLOW_FILE_EXT) }
