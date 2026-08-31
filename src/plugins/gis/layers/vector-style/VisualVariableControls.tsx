/** 文件职责：layers 前端功能：可复用界面组件。 */
import type { TranslationKeys } from "@/app/i18n";
import type { ClassificationMethod, FieldDescriptor } from "@/shared/geo";
import type {
  SortVariableDraft,
  VisualVariableDraft,
} from "./graduatedStyleModel";

export function ControlRow({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center gap-3">
      <span className="text-xs text-text-muted w-24 shrink-0">{label}</span>
      {children}
    </div>
  );
}

type LayerTranslations = TranslationKeys["layers"];
interface VisualVariableControlsProps {
  t: LayerTranslations;
  numericFields: FieldDescriptor[];
  geometryLabel: string;
  sizeVariable: VisualVariableDraft;
  onSizeVariableChange: (next: VisualVariableDraft) => void;
  opacityVariable: VisualVariableDraft;
  onOpacityVariableChange: (next: VisualVariableDraft) => void;
  sortVariable: SortVariableDraft;
  onSortVariableChange: (next: SortVariableDraft) => void;
}

export function VisualVariableControls({
  t,
  numericFields,
  geometryLabel,
  sizeVariable,
  onSizeVariableChange,
  opacityVariable,
  onOpacityVariableChange,
  sortVariable,
  onSortVariableChange,
}: VisualVariableControlsProps) {
  return (
    <div className="border-t-[0.5px] border-border/20 pt-3 mt-1 space-y-3">
      <div className="text-2xs text-text-muted font-semibold uppercase tracking-wider">
        {t.visualVariables}
      </div>
      <VariableEditor
        title={geometryLabel}
        fields={numericFields}
        draft={sizeVariable}
        onChange={onSizeVariableChange}
        min={0.5}
        max={32}
        step={0.5}
        suffix="px"
        t={t}
      />
      <VariableEditor
        title={t.opacity}
        fields={numericFields}
        draft={opacityVariable}
        onChange={onOpacityVariableChange}
        min={0}
        max={1}
        step={0.05}
        suffix="%"
        format={(value) => String(Math.round(value * 100))}
        t={t}
      />
      <SortVariableEditor
        fields={numericFields}
        draft={sortVariable}
        onChange={onSortVariableChange}
      />
    </div>
  );
}

function SortVariableEditor({
  fields,
  draft,
  onChange,
}: {
  fields: FieldDescriptor[];
  draft: SortVariableDraft;
  onChange: (next: SortVariableDraft) => void;
}) {
  const patch = (updates: Partial<SortVariableDraft>) =>
    onChange({ ...draft, ...updates });
  return (
    <div className="rounded-lg bg-bg-secondary/70 border-[0.5px] border-border/25 px-2.5 py-2 space-y-2">
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={draft.enabled}
          onChange={(e) => patch({ enabled: e.target.checked })}
          className="w-3.5 h-3.5 accent-accent-primary"
        />
        <span className="text-xs font-medium text-text-secondary">
          绘制顺序
        </span>
      </label>
      {draft.enabled && (
        <div className="space-y-2">
          <ControlRow label="字段">
            <select
              value={draft.field}
              onChange={(e) => patch({ field: e.target.value })}
              className="flex-1 bg-bg-primary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
            >
              {fields.length === 0 && <option value="">无数值字段</option>}
              {fields.map((field) => (
                <option key={field.name} value={field.name}>
                  {field.name}
                </option>
              ))}
            </select>
          </ControlRow>
          <ControlRow label="方式">
            <select
              value={draft.order}
              onChange={(e) =>
                patch({ order: e.target.value as SortVariableDraft["order"] })
              }
              className="flex-1 bg-bg-primary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
            >
              <option value="descending">高值在上</option>
              <option value="ascending">低值在上</option>
            </select>
          </ControlRow>
        </div>
      )}
    </div>
  );
}

interface VariableEditorProps {
  t: LayerTranslations;
  title: string;
  fields: FieldDescriptor[];
  draft: VisualVariableDraft;
  onChange: (next: VisualVariableDraft) => void;
  min: number;
  max: number;
  step: number;
  suffix: string;
  format?: (value: number) => string;
}

function VariableEditor({
  t,
  title,
  fields,
  draft,
  onChange,
  min,
  max,
  step,
  suffix,
  format = (value) => value.toFixed(step < 1 ? 2 : 1),
}: VariableEditorProps) {
  const patch = (updates: Partial<VisualVariableDraft>) =>
    onChange({ ...draft, ...updates });
  return (
    <div className="rounded-lg bg-bg-secondary/70 border-[0.5px] border-border/25 px-2.5 py-2 space-y-2">
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={draft.enabled}
          onChange={(e) => patch({ enabled: e.target.checked })}
          className="w-3.5 h-3.5 accent-accent-primary"
        />
        <span className="text-xs font-medium text-text-secondary">{title}</span>
      </label>
      {draft.enabled && (
        <div className="space-y-2">
          <ControlRow label={t.field}>
            <select
              value={draft.field}
              onChange={(e) => patch({ field: e.target.value })}
              className="flex-1 bg-bg-primary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
            >
              {fields.length === 0 && (
                <option value="">{t.noNumericFields}</option>
              )}
              {fields.map((field) => (
                <option key={field.name} value={field.name}>
                  {field.name}
                </option>
              ))}
            </select>
          </ControlRow>
          <ControlRow label={t.method}>
            <select
              value={draft.method}
              onChange={(e) =>
                patch({ method: e.target.value as ClassificationMethod })
              }
              className="flex-1 bg-bg-primary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
            >
              <option value="quantile">{t.quantile}</option>
              <option value="equal-interval">{t.equalInterval}</option>
              <option value="jenks">{t.naturalBreaks}</option>
            </select>
          </ControlRow>
          <ControlRow label={t.classes}>
            <div className="flex items-center gap-2 flex-1">
              <input
                type="range"
                min={2}
                max={12}
                step={1}
                value={draft.classes}
                onChange={(e) => patch({ classes: parseInt(e.target.value) })}
                className="flex-1 h-1 accent-accent-primary cursor-pointer"
              />
              <span className="text-xs text-text-primary font-mono w-6 text-center tabular-nums">
                {draft.classes}
              </span>
            </div>
          </ControlRow>
          <div className="grid grid-cols-2 gap-2">
            <NumberControl
              label={t.min}
              value={draft.min}
              onChange={(value) => patch({ min: Math.min(value, draft.max) })}
              min={min}
              max={max}
              step={step}
              suffix={suffix}
              format={format}
            />
            <NumberControl
              label={t.max}
              value={draft.max}
              onChange={(value) => patch({ max: Math.max(value, draft.min) })}
              min={min}
              max={max}
              step={step}
              suffix={suffix}
              format={format}
            />
          </div>
        </div>
      )}
    </div>
  );
}

function NumberControl({
  label,
  value,
  onChange,
  min,
  max,
  step,
  suffix,
  format,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
  step: number;
  suffix: string;
  format: (value: number) => string;
}) {
  return (
    <label className="flex items-center gap-1.5 text-2xs text-text-muted">
      <span className="w-7">{label}</span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        className="min-w-0 flex-1 h-1 accent-accent-primary cursor-pointer"
      />
      <span className="w-10 text-right text-text-secondary font-mono tabular-nums">
        {format(value)}
        {suffix}
      </span>
    </label>
  );
}

export function OrderButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="flex items-center justify-center gap-1 px-2 py-1.5 rounded-lg bg-bg-secondary text-text-secondary border-[0.5px] border-border/35 hover:text-text-primary hover:bg-bg-hover disabled:opacity-35 disabled:cursor-not-allowed transition-colors"
      title={label}
    >
      {children}
      <span className="text-2xs">{label}</span>
    </button>
  );
}

// ─── Shared UI Components ───────────────────────────────────────
