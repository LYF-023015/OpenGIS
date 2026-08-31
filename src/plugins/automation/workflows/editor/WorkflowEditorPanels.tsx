/** 文件职责：workflows 前端功能：实现该文件名所对应的单一职责。 */
import { useEffect, useState } from "react";
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Circle,
  Copy,
  GitBranch,
  GripVertical,
  Plus,
  Settings2,
  Shield,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import { useT } from "@/app/i18n";
import type {
  WorkflowCondition,
  WorkflowEdge,
  WorkflowNode,
} from "../model/workflow-schema";
// ─── Step Card ───────────────────────────────────────────────────

interface StepCardProps {
  step: WorkflowNode;
  index: number;
  totalSteps: number;
  isSelected: boolean;
  isDragging: boolean;
  onClick: () => void;
  onRemove: () => void;
  onDuplicate: () => void;
}

export function StepCard({
  step,
  index,
  isSelected,
  isDragging,
  onClick,
  onRemove,
  onDuplicate,
}: StepCardProps) {
  const t = useT();
  const hasDescription = !!step.description?.trim();
  const hasContract =
    !!step.inputContract?.trim() || !!step.outputContract?.trim();
  const hasConditions = (step.conditions?.length ?? 0) > 0;
  const hasParams = Object.keys(step.params || {}).length > 0;

  return (
    <div
      onClick={onClick}
      className={`
        group relative rounded-xl border transition-all duration-150 cursor-pointer select-none
        ${isDragging ? "opacity-50 scale-[0.98]" : ""}
        ${
          isSelected
            ? "border-accent-primary bg-accent-primary/5 shadow-sm shadow-accent-primary/10"
            : "border-border bg-bg-secondary hover:border-accent-primary/30 hover:shadow-sm"
        }
      `}
    >
      <div className="flex items-start gap-2 px-2 py-3">
        {/* Drag handle */}
        <div
          className="w-5 h-7 flex items-center justify-center shrink-0 cursor-grab active:cursor-grabbing text-text-muted/40 hover:text-text-muted mt-0.5"
          title={t.workflow.dragToReorder}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <GripVertical className="w-3.5 h-3.5" />
        </div>

        {/* Step number badge */}
        <div
          className={`
          w-7 h-7 rounded-full flex items-center justify-center shrink-0 text-xs font-bold mt-0.5
          ${
            isSelected
              ? "bg-accent-primary text-white"
              : hasDescription
                ? "bg-accent-geo/15 text-accent-geo"
                : "bg-bg-tertiary text-text-muted"
          }
        `}
        >
          {index + 1}
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-[13px] font-medium text-text-primary truncate">
              {step.title || t.workflow.editor.untitledStep}
            </span>
            {/* Status badges */}
            <div className="flex items-center gap-1">
              {hasConditions && (
                <span
                  className="w-4 h-4 rounded flex items-center justify-center bg-emerald-500/10"
                  title="Has safe validation conditions"
                >
                  <Shield className="w-2.5 h-2.5 text-emerald-500" />
                </span>
              )}
              {hasParams && (
                <span
                  className="w-4 h-4 rounded flex items-center justify-center bg-blue-500/10"
                  title="Has parameters"
                >
                  <Settings2 className="w-2.5 h-2.5 text-blue-500" />
                </span>
              )}
              {hasContract && (
                <span
                  className="w-4 h-4 rounded flex items-center justify-center bg-accent-geo/10"
                  title="Has node handoff contract"
                >
                  <GitBranch className="w-2.5 h-2.5 text-accent-geo" />
                </span>
              )}
            </div>
          </div>

          {hasDescription ? (
            <p className="text-[11px] text-text-muted mt-0.5 line-clamp-2 leading-relaxed">
              {step.description}
            </p>
          ) : (
            <p className="text-[11px] text-text-muted/50 mt-0.5 italic">
              Click to add a description...
            </p>
          )}
        </div>

        {/* Actions (visible on hover) */}
        <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
          <ActionBtn
            icon={<Copy className="w-3 h-3" />}
            title="Duplicate"
            onClick={(e) => {
              e.stopPropagation();
              onDuplicate();
            }}
          />
          <ActionBtn
            icon={<Trash2 className="w-3 h-3" />}
            title="Remove"
            onClick={(e) => {
              e.stopPropagation();
              onRemove();
            }}
            danger
          />
        </div>
      </div>
    </div>
  );
}

function ActionBtn({
  icon,
  title,
  onClick,
  danger,
}: {
  icon: React.ReactNode;
  title: string;
  onClick: (e: React.MouseEvent) => void;
  danger?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`w-5 h-5 rounded flex items-center justify-center transition-colors ${
        danger
          ? "text-text-muted hover:text-accent-danger hover:bg-accent-danger/10"
          : "text-text-muted hover:text-text-primary hover:bg-bg-hover"
      }`}
    >
      {icon}
    </button>
  );
}

// ─── Step Inspector (right panel) ────────────────────────────────

interface StepInspectorProps {
  step: WorkflowNode;
  stepIndex: number;
  totalSteps: number;
  onUpdate: (patch: Partial<WorkflowNode>) => void;
  onRemove: () => void;
}

export function StepInspector({
  step,
  stepIndex,
  totalSteps,
  onUpdate,
  onRemove,
}: StepInspectorProps) {
  const t = useT();
  const [showConditions, setShowConditions] = useState(false);
  const [showParams, setShowParams] = useState(false);

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="h-9 border-b border-border flex items-center px-3 shrink-0">
        <span className="text-xs font-semibold text-text-secondary flex-1 truncate">
          {t.workflow.stepOf
            .replace("{index}", String(stepIndex + 1))
            .replace("{total}", String(totalSteps))}
        </span>
        <button
          onClick={onRemove}
          className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 transition-colors"
          title={t.workflow.deleteStep}
        >
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto scrollbar-thin p-3 space-y-4">
        {/* Title */}
        <Field
          label={t.workflow.inspector.stepTitle}
          hint={t.workflow.inspector.stepTitleHint}
        >
          <input
            value={step.title}
            onChange={(e) => onUpdate({ title: e.target.value })}
            placeholder={t.workflow.inspector.stepTitlePlaceholder}
            className="w-full bg-bg-tertiary border border-border rounded-lg px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary transition-colors"
          />
        </Field>

        {/* Description — the main instruction for the LLM */}
        <Field
          label={t.workflow.inspector.description}
          hint={t.workflow.inspector.descriptionHint}
        >
          <textarea
            value={step.description ?? ""}
            onChange={(e) => onUpdate({ description: e.target.value })}
            placeholder={t.workflow.inspector.descriptionPlaceholder}
            rows={5}
            className="w-full bg-bg-tertiary border border-border rounded-lg px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary transition-colors resize-y leading-relaxed"
          />
        </Field>

        {/* Node communication contracts */}
        <Field
          label={t.workflow.inspector.inputContract}
          hint={t.workflow.inspector.inputContractHint}
        >
          <textarea
            value={step.inputContract ?? ""}
            onChange={(e) => onUpdate({ inputContract: e.target.value })}
            placeholder={t.workflow.inspector.inputContractPlaceholder}
            rows={3}
            className="w-full bg-bg-tertiary border border-border rounded-lg px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary transition-colors resize-y leading-relaxed"
          />
        </Field>

        <Field
          label={t.workflow.inspector.outputContract}
          hint={t.workflow.inspector.outputContractHint}
        >
          <textarea
            value={step.outputContract ?? ""}
            onChange={(e) => onUpdate({ outputContract: e.target.value })}
            placeholder={t.workflow.inspector.outputContractPlaceholder}
            rows={3}
            className="w-full bg-bg-tertiary border border-border rounded-lg px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary transition-colors resize-y leading-relaxed"
          />
        </Field>

        <Field
          label="Execution type"
          hint="Choose the Java runtime boundary used by this node."
        >
          <div className="grid grid-cols-2 gap-2">
            <select
              value={step.type}
              onChange={(e) => {
                const type = e.target.value as WorkflowNode["type"];
                onUpdate({
                  type,
                  execution: {
                    kind: type,
                    ref: type === "agent_task" ? "gis-build" : "",
                  },
                });
              }}
              className="bg-bg-tertiary border border-border rounded-lg px-2 py-1.5 text-xs text-text-primary outline-none"
            >
              <option value="agent_task">Agent task</option>
              <option value="tool_call">Tool call</option>
              <option value="operation">Operation</option>
              <option value="java_script">Java script</option>
              <option value="subworkflow">Sub-workflow</option>
            </select>
            <input
              value={step.execution.ref}
              onChange={(e) =>
                onUpdate({
                  execution: { ...step.execution, ref: e.target.value },
                })
              }
              placeholder="profile / tool / operation / .java / workflow id"
              className="bg-bg-tertiary border border-border rounded-lg px-2 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary"
            />
          </div>
        </Field>

        {/* Max retries */}
        <Field
          label={t.workflow.inspector.maxRetries}
          hint={t.workflow.inspector.maxRetriesHint}
        >
          <input
            type="number"
            min={1}
            max={10}
            value={step.retryPolicy?.maxAttempts ?? 1}
            onChange={(e) =>
              onUpdate({
                retryPolicy: {
                  maxAttempts: parseInt(e.target.value) || 1,
                  backoffMs: step.retryPolicy?.backoffMs ?? 0,
                },
              })
            }
            className="w-20 bg-bg-tertiary border border-border rounded-lg px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary transition-colors"
          />
        </Field>

        {/* Validation Hooks */}
        <CollapsibleSection
          title="Safe conditions"
          icon={<Shield className="w-3.5 h-3.5 text-emerald-500" />}
          count={step.conditions?.length ?? 0}
          open={showConditions}
          onToggle={() => setShowConditions(!showConditions)}
        >
          <ConditionEditor
            conditions={step.conditions ?? []}
            onChange={(conditions) => onUpdate({ conditions })}
          />
        </CollapsibleSection>

        {/* Parameters */}
        <CollapsibleSection
          title={t.workflow.params.title}
          icon={<Settings2 className="w-3.5 h-3.5 text-blue-500" />}
          count={Object.keys(step.params || {}).length}
          open={showParams}
          onToggle={() => setShowParams(!showParams)}
        >
          <ParamsEditor
            params={step.params || {}}
            onChange={(params) => onUpdate({ params })}
          />
        </CollapsibleSection>

        {/* Notes */}
        <Field
          label={t.workflow.inspector.notes}
          hint={t.workflow.inspector.notesHint}
        >
          <textarea
            value={step.notes ?? ""}
            onChange={(e) => onUpdate({ notes: e.target.value || undefined })}
            rows={2}
            placeholder={t.workflow.inspector.notesPlaceholder}
            className="w-full bg-bg-tertiary border border-border rounded-lg px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent-primary transition-colors resize-y"
          />
        </Field>

        <div className="pt-2 border-t border-border text-2xs text-text-muted font-mono break-all">
          id: {step.id}
        </div>
      </div>
    </div>
  );
}

// ─── Workflow Overview (when no step selected) ───────────────────

interface WorkflowOverviewProps {
  path: string;
  doc: {
    name: string;
    description?: string;
    nodes: WorkflowNode[];
    edges: WorkflowEdge[];
  };
  steps: WorkflowNode[];
  onUpdateDoc: (patch: Record<string, any>) => void;
}

export function WorkflowOverview({ path, steps }: WorkflowOverviewProps) {
  const t = useT();
  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="h-9 border-b border-border flex items-center px-3 shrink-0">
        <span className="text-xs font-semibold text-text-secondary">
          {t.workflow.overview}
        </span>
      </div>

      <div className="flex-1 overflow-y-auto scrollbar-thin p-3 space-y-4">
        {/* Pipeline preview */}
        <div className="space-y-1">
          <label className="text-2xs text-text-muted uppercase tracking-wider font-medium">
            {t.workflow.pipeline}
          </label>
          <div className="bg-bg-tertiary rounded-lg p-3 space-y-1.5">
            {steps.length === 0 ? (
              <p className="text-2xs text-text-muted/60 italic text-center py-2">
                {t.workflow.noStepsDefined}
              </p>
            ) : (
              steps.map((step, i) => (
                <div key={step.id}>
                  <div className="flex items-center gap-2">
                    <div
                      className={`w-5 h-5 rounded-full flex items-center justify-center text-2xs font-bold ${
                        step.description?.trim()
                          ? "bg-accent-geo/15 text-accent-geo"
                          : "bg-bg-primary text-text-muted"
                      }`}
                    >
                      {i + 1}
                    </div>
                    <span className="text-2xs text-text-secondary truncate flex-1">
                      {step.title || t.workflow.editor.untitled}
                    </span>
                    {step.description?.trim() ? (
                      <CheckCircle2 className="w-3 h-3 text-emerald-500 shrink-0" />
                    ) : (
                      <Circle className="w-3 h-3 text-text-muted/30 shrink-0" />
                    )}
                  </div>
                  {i < steps.length - 1 && (
                    <div className="ml-2.5 border-l border-border h-1.5" />
                  )}
                </div>
              ))
            )}
          </div>
        </div>

        {/* Stats */}
        <div className="space-y-1">
          <label className="text-2xs text-text-muted uppercase tracking-wider font-medium">
            {t.workflow.statistics}
          </label>
          <div className="grid grid-cols-2 gap-2">
            <StatCard label={t.workflow.stats.steps} value={steps.length} />
            <StatCard
              label={t.workflow.stats.withHooks}
              value={
                steps.filter((s) => (s.conditions?.length ?? 0) > 0).length
              }
            />
            <StatCard
              label={t.workflow.stats.configured}
              value={steps.filter((s) => !!s.description?.trim()).length}
            />
            <StatCard
              label={t.workflow.stats.withParams}
              value={
                steps.filter((s) => Object.keys(s.params || {}).length > 0)
                  .length
              }
            />
          </div>
        </div>

        {/* Info */}
        <div className="pt-2 border-t border-border space-y-1 text-2xs text-text-muted">
          <div className="font-mono break-all">Path: {path}</div>
        </div>

        <div className="pt-2 text-2xs text-text-muted italic">
          {t.workflow.selectStepHint}
        </div>
      </div>
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="bg-bg-tertiary rounded-lg px-3 py-2 text-center">
      <div className="text-base font-bold text-text-primary">{value}</div>
      <div className="text-2xs text-text-muted">{label}</div>
    </div>
  );
}

// ─── Hook Editor ─────────────────────────────────────────────────

function ConditionEditor({
  conditions,
  onChange,
}: {
  conditions: WorkflowCondition[];
  onChange: (conditions: WorkflowCondition[]) => void;
}) {
  const addCondition = () => {
    onChange([
      ...conditions,
      {
        expression: { exists: { var: "output" } },
        description: "",
        onFalse: "fail",
      },
    ]);
  };

  const updateCondition = (i: number, patch: Partial<WorkflowCondition>) => {
    const next = [...conditions];
    next[i] = { ...next[i], ...patch };
    onChange(next);
  };

  return (
    <div className="space-y-2">
      <p className="text-2xs text-text-muted/70 leading-relaxed">
        JSON Logic subset: var, exists, ==, !=, &gt;, &gt;=, &lt;, &lt;=, and,
        or, !, in. No source code is evaluated.
      </p>
      {conditions.map((condition, i) => (
        <div
          key={i}
          className="bg-bg-primary rounded-lg p-2.5 space-y-1.5 border border-border/50"
        >
          <div className="flex items-start gap-1.5">
            <ConditionExpressionInput
              expression={condition.expression}
              onChange={(expression) => updateCondition(i, { expression })}
            />
            <button
              onClick={() =>
                onChange(conditions.filter((_, index) => index !== i))
              }
              className="w-5 h-5 rounded flex items-center justify-center text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 transition-colors shrink-0"
            >
              <X className="w-3 h-3" />
            </button>
          </div>
          <div className="flex items-center gap-2">
            <input
              value={condition.description ?? ""}
              onChange={(e) =>
                updateCondition(i, { description: e.target.value })
              }
              placeholder="What this condition verifies"
              className="flex-1 bg-bg-tertiary border border-border rounded px-2 py-1 text-2xs text-text-secondary outline-none focus:border-accent-primary"
            />
            <select
              value={condition.onFalse ?? "fail"}
              onChange={(e) =>
                updateCondition(i, {
                  onFalse: e.target.value as WorkflowCondition["onFalse"],
                })
              }
              className="bg-bg-tertiary border border-border rounded px-1.5 py-1 text-2xs text-text-secondary outline-none"
            >
              <option value="fail">Fail</option>
              <option value="retry">Retry</option>
              <option value="skip">Skip</option>
            </select>
          </div>
        </div>
      ))}
      <button
        onClick={addCondition}
        className="w-full text-2xs text-text-muted hover:text-accent-primary border border-dashed border-border hover:border-accent-primary/50 rounded-lg py-1.5 transition-colors flex items-center justify-center gap-1"
      >
        <Plus className="w-3 h-3" />
        Add safe condition
      </button>
    </div>
  );
}

function ConditionExpressionInput({
  expression,
  onChange,
}: {
  expression: WorkflowCondition["expression"];
  onChange: (expression: WorkflowCondition["expression"]) => void;
}) {
  const [raw, setRaw] = useState(() => JSON.stringify(expression));
  const [valid, setValid] = useState(true);
  useEffect(() => setRaw(JSON.stringify(expression)), [expression]);
  return (
    <input
      value={raw}
      onChange={(event) => {
        setRaw(event.target.value);
        try {
          onChange(JSON.parse(event.target.value));
          setValid(true);
        } catch {
          setValid(false);
        }
      }}
      title={valid ? "Valid safe condition JSON" : "Invalid JSON"}
      placeholder={'{"exists":{"var":"output"}}'}
      className={`flex-1 bg-bg-tertiary border rounded px-2 py-1 text-2xs text-text-primary outline-none font-mono ${valid ? "border-border focus:border-accent-primary" : "border-accent-danger"}`}
    />
  );
}

// ─── Params Editor ───────────────────────────────────────────────

function ParamsEditor({
  params,
  onChange,
}: {
  params: Record<string, unknown>;
  onChange: (params: Record<string, unknown>) => void;
}) {
  const t = useT();
  const entries = Object.entries(params);

  const addParam = () => {
    const key = `param_${entries.length + 1}`;
    onChange({ ...params, [key]: "" });
  };

  const updateKey = (oldKey: string, newKey: string) => {
    if (newKey === oldKey) return;
    const newParams: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(params)) {
      newParams[k === oldKey ? newKey : k] = v;
    }
    onChange(newParams);
  };

  const updateValue = (key: string, value: string) => {
    // Try to parse as JSON, fall back to string
    let parsed: unknown = value;
    try {
      parsed = JSON.parse(value);
    } catch {
      parsed = value;
    }
    onChange({ ...params, [key]: parsed });
  };

  const removeParam = (key: string) => {
    const next = { ...params };
    delete next[key];
    onChange(next);
  };

  return (
    <div className="space-y-2">
      <p className="text-2xs text-text-muted/70 leading-relaxed">
        {t.workflow.params.description}
      </p>

      {entries.map(([key, value]) => (
        <div key={key} className="flex items-center gap-1.5">
          <input
            value={key}
            onChange={(e) => updateKey(key, e.target.value)}
            placeholder={t.workflow.params.keyPlaceholder}
            className="w-24 bg-bg-tertiary border border-border rounded px-2 py-1 text-2xs text-text-primary outline-none focus:border-accent-primary font-mono"
          />
          <input
            value={typeof value === "string" ? value : JSON.stringify(value)}
            onChange={(e) => updateValue(key, e.target.value)}
            placeholder={t.workflow.params.valuePlaceholder}
            className="flex-1 bg-bg-tertiary border border-border rounded px-2 py-1 text-2xs text-text-primary outline-none focus:border-accent-primary"
          />
          <button
            onClick={() => removeParam(key)}
            className="w-5 h-5 rounded flex items-center justify-center text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 transition-colors shrink-0"
          >
            <X className="w-3 h-3" />
          </button>
        </div>
      ))}

      <button
        onClick={addParam}
        className="w-full text-2xs text-text-muted hover:text-accent-primary border border-dashed border-border hover:border-accent-primary/50 rounded-lg py-1.5 transition-colors flex items-center justify-center gap-1"
      >
        <Plus className="w-3 h-3" />
        {t.workflow.params.addParam}
      </button>
    </div>
  );
}

// ─── Shared UI components ────────────────────────────────────────

export function ToolbarButton({
  icon,
  label,
  onClick,
  disabled,
  accent,
}: {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
  disabled?: boolean;
  accent?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`
        h-7 px-2.5 rounded flex items-center gap-1.5 text-xs transition-colors
        ${
          disabled
            ? "text-text-muted/40 cursor-not-allowed"
            : accent
              ? "text-accent-primary hover:bg-accent-primary/10"
              : "text-text-secondary hover:text-text-primary hover:bg-bg-hover"
        }
      `}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1">
      <label className="text-2xs text-text-muted uppercase tracking-wider font-medium">
        {label}
      </label>
      {children}
      {hint && (
        <p className="text-2xs text-text-muted/70 italic leading-relaxed">
          {hint}
        </p>
      )}
    </div>
  );
}

function CollapsibleSection({
  title,
  icon,
  count,
  open,
  onToggle,
  children,
}: {
  title: string;
  icon: React.ReactNode;
  count: number;
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="border border-border rounded-lg overflow-hidden">
      <button
        onClick={onToggle}
        className="w-full flex items-center gap-2 px-3 py-2 text-xs text-text-secondary hover:text-text-primary hover:bg-bg-hover transition-colors"
      >
        {open ? (
          <ChevronDown className="w-3 h-3 text-text-muted" />
        ) : (
          <ChevronRight className="w-3 h-3 text-text-muted" />
        )}
        {icon}
        <span className="flex-1 text-left font-medium">{title}</span>
        {count > 0 && (
          <span className="text-2xs bg-accent-primary/10 text-accent-primary px-1.5 py-0.5 rounded-full">
            {count}
          </span>
        )}
      </button>
      {open && (
        <div className="px-3 pb-3 pt-1 border-t border-border/50">
          {children}
        </div>
      )}
    </div>
  );
}

export function EmptyWorkflowState({ onAddStep }: { onAddStep: () => void }) {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-8 h-full">
      <div className="text-center max-w-xs">
        <div className="w-14 h-14 rounded-2xl bg-accent-geo/10 flex items-center justify-center mx-auto mb-4">
          <Zap className="w-7 h-7 text-accent-geo/60" />
        </div>
        <h3 className="text-sm font-semibold text-text-primary mb-1.5">
          {t.workflow.buildTitle}
        </h3>
        <p className="text-xs text-text-muted leading-relaxed mb-4">
          {t.workflow.buildDescription}
        </p>

        <div className="space-y-2 text-left mb-5">
          <GuideItem num={1} text={t.workflow.guide1} />
          <GuideItem num={2} text={t.workflow.guide2} />
          <GuideItem num={3} text={t.workflow.guide3} />
        </div>

        <button
          onClick={onAddStep}
          className="inline-flex items-center gap-2 px-4 py-2 bg-accent-primary text-white text-xs font-medium rounded-lg hover:bg-accent-primary/90 transition-colors shadow-sm"
        >
          <Plus className="w-3.5 h-3.5" />
          {t.workflow.addFirstStep}
        </button>
      </div>
    </div>
  );
}

function GuideItem({ num, text }: { num: number; text: string }) {
  return (
    <div className="flex items-start gap-2.5">
      <div className="w-5 h-5 rounded-full bg-accent-primary/10 flex items-center justify-center shrink-0 mt-0.5">
        <span className="text-2xs font-bold text-accent-primary">{num}</span>
      </div>
      <span className="text-2xs text-text-secondary leading-relaxed">
        {text}
      </span>
    </div>
  );
}
