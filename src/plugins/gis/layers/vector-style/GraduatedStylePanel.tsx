/** 文件职责：layers 前端功能：页面级界面与交互编排。 */
/**
 * GraduatedStylePanel — Classification rendering UI panel.
 *
 * Provides ArcGIS Pro / QGIS-style graduated & categorized rendering controls:
 * - Render mode selection (graduated / categorized)
 * - Attribute field picker
 * - Classification method (quantile / equal-interval / jenks)
 * - Number of classes (2–10)
 * - Color ramp presets + individual color editing
 * - Live preview legend
 *
 * This component is intentionally decoupled from LayerPanel to avoid bloating
 * the main layer list code. It is rendered as a modal/popover when the user
 * clicks the "Classification" button on a layer.
 */
import { useState, useMemo, useCallback, useEffect } from "react";
import {
  X,
  ChevronDown,
  Palette,
  BarChart3,
  Tag,
  Check,
  ArrowUp,
  ArrowDown,
  ChevronsUp,
  ChevronsDown,
} from "lucide-react";
import { useT } from "@/app/i18n";
import type { TranslationKeys } from "@/app/i18n";
import type {
  MapLayerDefinition,
  LayerStyle,
  FieldDescriptor,
  ClassificationMethod,
} from "@/shared/geo";
import { useMapStore } from "@/plugins/gis/map/model/mapStore";
import {
  COLOR_RAMPS,
  draftFromVariable,
  equalIntervalBreaks,
  getAllFields,
  getNumericFields,
  quantileBreaks,
  sampleNumericValues,
  sampleUniqueValues,
  sortDraftFromVariable,
  sortVariableFromDraft,
  variableFromDraft,
  type SortVariableDraft,
  type VisualVariableDraft,
  type ColorRamp,
} from "./graduatedStyleModel";
import {
  ControlRow,
  OrderButton,
  VisualVariableControls,
} from "./VisualVariableControls";

// ─── Component Props ────────────────────────────────────────────

interface GraduatedStylePanelProps {
  layer: MapLayerDefinition;
  onClose: () => void;
}

type RenderMode = "graduated" | "categorized";
type LayerTranslations = TranslationKeys["layers"];

function RampPreview({
  colors,
  className,
}: {
  colors: string[];
  className?: string;
}) {
  return (
    <div className={`flex overflow-hidden ${className || ""}`}>
      {colors.map((color, index) => (
        <div
          key={index}
          className="flex-1 h-full"
          style={{ backgroundColor: color }}
        />
      ))}
    </div>
  );
}

// ─── Main Component ─────────────────────────────────────────────

export function GraduatedStylePanel({
  layer,
  onClose,
}: GraduatedStylePanelProps) {
  const t = useT();
  const updateLayerStyle = useMapStore((s) => s.updateLayerStyle);
  const layers = useMapStore((s) => s.layers);
  const reorderLayers = useMapStore((s) => s.reorderLayers);

  // Determine initial mode from current style
  const initialMode: RenderMode =
    layer.style.renderType === "categorized" ? "categorized" : "graduated";

  const [mode, setMode] = useState<RenderMode>(initialMode);
  const [selectedRampId, setSelectedRampId] = useState("viridis");
  const [showRampPicker, setShowRampPicker] = useState(false);

  // ── Graduated state ──
  const numericFields = useMemo(() => getNumericFields(layer), [layer]);
  const [gradField, setGradField] = useState<string>(
    layer.style.graduated?.field || numericFields[0]?.name || "",
  );
  const [gradMethod, setGradMethod] = useState<ClassificationMethod>(
    layer.style.graduated?.method || "quantile",
  );
  const [gradClasses, setGradClasses] = useState<number>(
    layer.style.graduated?.classes || 5,
  );
  const [gradPalette, setGradPalette] = useState<string[]>(
    () => layer.style.graduated?.palette || COLOR_RAMPS[0].colors(gradClasses),
  );

  // ── Categorized state ──
  const allFields = useMemo(() => getAllFields(layer), [layer]);
  const [catField, setCatField] = useState<string>(
    layer.style.categorized?.field || allFields[0]?.name || "",
  );
  const [catMaxCategories, setCatMaxCategories] = useState<number>(
    layer.style.categorized?.maxCategories || 10,
  );
  const [catColors, setCatColors] = useState<Record<string, string>>(
    () => layer.style.categorized?.colors || {},
  );

  // ── Common style state (shared by both modes) ──
  const isPointGeom =
    layer.data.kind === "vector" &&
    (layer.data.geometryType === "Point" ||
      layer.data.geometryType === "MultiPoint");
  const isFillGeom =
    layer.data.kind === "vector" &&
    (layer.data.geometryType === "Polygon" ||
      layer.data.geometryType === "MultiPolygon");
  const [strokeWidth, setStrokeWidth] = useState(layer.style.strokeWidth);
  const [radius, setRadius] = useState(layer.style.radius ?? 5);
  const [strokeColor, setStrokeColor] = useState(layer.style.strokeColor);
  const [fillOpacity, setFillOpacity] = useState(
    layer.style.fillOpacity ?? layer.style.opacity,
  );
  const [sizeVariable, setSizeVariable] = useState<VisualVariableDraft>(() =>
    draftFromVariable(
      layer.style.sizeVariable,
      numericFields[0]?.name || "",
      isPointGeom ? [3, 14] : [1, 8],
    ),
  );
  const [opacityVariable, setOpacityVariable] = useState<VisualVariableDraft>(
    () =>
      draftFromVariable(
        layer.style.opacityVariable,
        numericFields[0]?.name || "",
        [0.25, layer.style.opacity],
      ),
  );
  const [sortVariable, setSortVariable] = useState<SortVariableDraft>(() =>
    sortDraftFromVariable(
      layer.style.sortVariable,
      numericFields[0]?.name || "",
    ),
  );

  useEffect(() => {
    setSizeVariable(
      draftFromVariable(
        layer.style.sizeVariable,
        numericFields[0]?.name || "",
        isPointGeom ? [3, 14] : [1, 8],
      ),
    );
    setOpacityVariable(
      draftFromVariable(
        layer.style.opacityVariable,
        numericFields[0]?.name || "",
        [0.25, layer.style.opacity],
      ),
    );
    setSortVariable(
      sortDraftFromVariable(
        layer.style.sortVariable,
        numericFields[0]?.name || "",
      ),
    );
  }, [
    layer.style.sizeVariable,
    layer.style.opacityVariable,
    layer.style.sortVariable,
    layer.style.opacity,
    numericFields,
    isPointGeom,
  ]);

  const currentLayerIndex = layers.findIndex((item) => item.id === layer.id);
  const canMoveDown = currentLayerIndex > 0;
  const canMoveUp =
    currentLayerIndex >= 0 && currentLayerIndex < layers.length - 1;
  const moveLayerTo = useCallback(
    (toIndex: number) => {
      if (currentLayerIndex < 0 || toIndex === currentLayerIndex) return;
      reorderLayers(
        currentLayerIndex,
        Math.max(0, Math.min(layers.length - 1, toIndex)),
      );
    },
    [currentLayerIndex, layers.length, reorderLayers],
  );

  // ── Computed breaks for graduated ──
  const gradBreaks = useMemo(() => {
    if (!gradField) return [];
    const values = sampleNumericValues(layer, gradField);
    if (values.length === 0) return [];
    if (gradMethod === "equal-interval")
      return equalIntervalBreaks(values, gradClasses);
    return quantileBreaks(values, gradClasses); // quantile / jenks fallback
  }, [layer, gradField, gradMethod, gradClasses]);

  // ── Computed unique values for categorized ──
  const catUniqueValues = useMemo(() => {
    if (!catField) return [];
    return sampleUniqueValues(layer, catField).slice(0, catMaxCategories);
  }, [layer, catField, catMaxCategories]);

  // ── Sync palette when ramp or class count changes ──
  useEffect(() => {
    const ramp =
      COLOR_RAMPS.find((r) => r.id === selectedRampId) || COLOR_RAMPS[0];
    if (mode === "graduated") {
      setGradPalette(ramp.colors(gradClasses));
    }
    if (mode === "categorized") {
      const colors = ramp.colors(catUniqueValues.length || catMaxCategories);
      const newCatColors: Record<string, string> = {};
      catUniqueValues.forEach((v, i) => {
        newCatColors[v] = colors[i % colors.length];
      });
      setCatColors(newCatColors);
    }
  }, [selectedRampId, gradClasses, mode, catUniqueValues, catMaxCategories]);

  // ── Apply ──
  const handleApply = useCallback(() => {
    const commonUpdates: Partial<LayerStyle> = {
      strokeWidth,
      strokeColor,
      ...(isPointGeom ? { radius } : {}),
      ...(isFillGeom ? { fillOpacity } : {}),
      sizeVariable: variableFromDraft(sizeVariable),
      opacityVariable: variableFromDraft(opacityVariable),
      sortVariable: sortVariableFromDraft(sortVariable),
    };
    if (mode === "graduated") {
      const updates: Partial<LayerStyle> = {
        ...commonUpdates,
        renderType: "graduated",
        graduated: {
          field: gradField,
          method: gradMethod,
          classes: gradClasses,
          breaks: gradBreaks,
          palette: gradPalette,
        },
      };
      updateLayerStyle(layer.id, updates);
    } else {
      const updates: Partial<LayerStyle> = {
        ...commonUpdates,
        renderType: "categorized",
        categorized: {
          field: catField,
          maxCategories: catMaxCategories,
          colors: catColors,
        },
      };
      updateLayerStyle(layer.id, updates);
    }
    onClose();
  }, [
    mode,
    gradField,
    gradMethod,
    gradClasses,
    gradBreaks,
    gradPalette,
    catField,
    catMaxCategories,
    catColors,
    layer.id,
    updateLayerStyle,
    onClose,
    strokeWidth,
    strokeColor,
    radius,
    fillOpacity,
    isPointGeom,
    isFillGeom,
    sizeVariable,
    opacityVariable,
    sortVariable,
  ]);

  // ── Reset to single-color ──
  const handleReset = useCallback(() => {
    // Determine the default renderType based on geometry
    let defaultType: LayerStyle["renderType"] = "fill";
    if (layer.data.kind === "vector") {
      const gt = layer.data.geometryType;
      if (gt === "Point" || gt === "MultiPoint") defaultType = "circle";
      else if (gt === "LineString" || gt === "MultiLineString")
        defaultType = "line";
    }
    updateLayerStyle(layer.id, {
      renderType: defaultType,
      graduated: undefined,
      categorized: undefined,
      sizeVariable: undefined,
      opacityVariable: undefined,
      sortVariable: undefined,
    });
    onClose();
  }, [layer, updateLayerStyle, onClose]);

  const selectedRamp =
    COLOR_RAMPS.find((r) => r.id === selectedRampId) || COLOR_RAMPS[0];

  return (
    <div className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-bg-primary border-[0.5px] border-border/35 rounded-xl shadow-2xl w-[420px] max-h-[85vh] flex flex-col overflow-hidden animate-fade-in">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b-[0.5px] border-border/20 shrink-0">
          <div className="flex items-center gap-2">
            <Palette className="w-4 h-4 text-accent-primary" />
            <span className="text-sm font-semibold text-text-primary">
              {t.layers.classificationRenderer}
            </span>
          </div>
          <button
            onClick={onClose}
            className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-text-primary hover:bg-bg-hover transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Mode tabs */}
        <div className="flex border-b-[0.5px] border-border/20 shrink-0">
          <button
            onClick={() => setMode("graduated")}
            className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 text-xs font-medium transition-colors ${
              mode === "graduated"
                ? "text-accent-primary border-b-[0.5px] border-accent-primary/60 bg-accent-primary/5"
                : "text-text-muted hover:text-text-secondary"
            }`}
          >
            <BarChart3 className="w-3.5 h-3.5" />
            {t.layers.graduated}
          </button>
          <button
            onClick={() => setMode("categorized")}
            className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 text-xs font-medium transition-colors ${
              mode === "categorized"
                ? "text-accent-primary border-b-[0.5px] border-accent-primary/60 bg-accent-primary/5"
                : "text-text-muted hover:text-text-secondary"
            }`}
          >
            <Tag className="w-3.5 h-3.5" />
            {t.layers.categorized}
          </button>
        </div>

        {/* Body */}
        <div
          className="flex-1 overflow-y-auto px-4 py-3 space-y-4"
          style={{ scrollbarWidth: "thin" }}
        >
          {mode === "graduated" ? (
            <GraduatedControls
              numericFields={numericFields}
              field={gradField}
              onFieldChange={setGradField}
              method={gradMethod}
              onMethodChange={setGradMethod}
              classes={gradClasses}
              onClassesChange={setGradClasses}
              palette={gradPalette}
              onPaletteChange={setGradPalette}
              breaks={gradBreaks}
              selectedRamp={selectedRamp}
              showRampPicker={showRampPicker}
              onToggleRampPicker={() => setShowRampPicker(!showRampPicker)}
              onSelectRamp={(id) => {
                setSelectedRampId(id);
                setShowRampPicker(false);
              }}
              t={t.layers}
            />
          ) : (
            <CategorizedControls
              allFields={allFields}
              field={catField}
              onFieldChange={setCatField}
              maxCategories={catMaxCategories}
              onMaxCategoriesChange={setCatMaxCategories}
              uniqueValues={catUniqueValues}
              colors={catColors}
              onColorsChange={setCatColors}
              selectedRamp={selectedRamp}
              showRampPicker={showRampPicker}
              onToggleRampPicker={() => setShowRampPicker(!showRampPicker)}
              onSelectRamp={(id) => {
                setSelectedRampId(id);
                setShowRampPicker(false);
              }}
              t={t.layers}
            />
          )}

          {/* Common style controls — shared by both modes */}
          <div className="border-t-[0.5px] border-border/20 pt-3 mt-1 space-y-2.5">
            <div className="text-2xs text-text-muted font-semibold uppercase tracking-wider mb-2">
              {t.layers.commonStyle}
            </div>

            {/* Stroke color */}
            {(isFillGeom || isPointGeom) && (
              <ControlRow label={t.layers.strokeColor}>
                <div className="flex items-center gap-2 flex-1">
                  <label
                    className="w-5 h-5 rounded border-[0.5px] border-border/35 shrink-0 cursor-pointer relative overflow-hidden"
                    style={{ backgroundColor: strokeColor }}
                  >
                    <input
                      type="color"
                      value={strokeColor}
                      onChange={(e) => setStrokeColor(e.target.value)}
                      className="absolute inset-0 opacity-0 cursor-pointer"
                    />
                  </label>
                  <span className="text-2xs text-text-secondary font-mono">
                    {strokeColor}
                  </span>
                </div>
              </ControlRow>
            )}

            {/* Stroke width */}
            <ControlRow label={t.layers.strokeWidth}>
              <div className="flex items-center gap-2 flex-1">
                <input
                  type="range"
                  min={0}
                  max={isPointGeom ? 5 : 10}
                  step={0.5}
                  value={strokeWidth}
                  onChange={(e) => setStrokeWidth(parseFloat(e.target.value))}
                  className="flex-1 h-1 accent-accent-primary cursor-pointer"
                />
                <span className="text-xs text-text-primary font-mono w-8 text-right tabular-nums">
                  {strokeWidth.toFixed(1)}
                </span>
              </div>
            </ControlRow>

            {/* Point radius */}
            {isPointGeom && (
              <ControlRow label={t.layers.pointRadius}>
                <div className="flex items-center gap-2 flex-1">
                  <input
                    type="range"
                    min={1}
                    max={30}
                    step={0.5}
                    value={radius}
                    onChange={(e) => setRadius(parseFloat(e.target.value))}
                    className="flex-1 h-1 accent-accent-primary cursor-pointer"
                  />
                  <span className="text-xs text-text-primary font-mono w-8 text-right tabular-nums">
                    {radius.toFixed(1)}
                  </span>
                </div>
              </ControlRow>
            )}

            {/* Fill opacity */}
            {isFillGeom && (
              <ControlRow label={t.layers.fillOpacity}>
                <div className="flex items-center gap-2 flex-1">
                  <input
                    type="range"
                    min={0}
                    max={1}
                    step={0.05}
                    value={fillOpacity}
                    onChange={(e) => setFillOpacity(parseFloat(e.target.value))}
                    className="flex-1 h-1 accent-accent-primary cursor-pointer"
                  />
                  <span className="text-xs text-text-primary font-mono w-8 text-right tabular-nums">
                    {Math.round(fillOpacity * 100)}%
                  </span>
                </div>
              </ControlRow>
            )}
          </div>

          <VisualVariableControls
            numericFields={numericFields}
            geometryLabel={
              isPointGeom
                ? t.layers.pointSize
                : isFillGeom
                  ? t.layers.borderWidth
                  : t.layers.lineWidth
            }
            sizeVariable={sizeVariable}
            onSizeVariableChange={setSizeVariable}
            opacityVariable={opacityVariable}
            onOpacityVariableChange={setOpacityVariable}
            sortVariable={sortVariable}
            onSortVariableChange={setSortVariable}
            t={t.layers}
          />

          <div className="border-t-[0.5px] border-border/20 pt-3 mt-1 space-y-2.5">
            <div className="text-2xs text-text-muted font-semibold uppercase tracking-wider mb-2">
              {t.layers.layerOrder}
            </div>
            <div className="grid grid-cols-4 gap-1.5">
              <OrderButton
                label={t.layers.bottom}
                disabled={!canMoveDown}
                onClick={() => moveLayerTo(0)}
              >
                <ChevronsDown className="w-3.5 h-3.5" />
              </OrderButton>
              <OrderButton
                label={t.layers.down}
                disabled={!canMoveDown}
                onClick={() => moveLayerTo(currentLayerIndex - 1)}
              >
                <ArrowDown className="w-3.5 h-3.5" />
              </OrderButton>
              <OrderButton
                label={t.layers.up}
                disabled={!canMoveUp}
                onClick={() => moveLayerTo(currentLayerIndex + 1)}
              >
                <ArrowUp className="w-3.5 h-3.5" />
              </OrderButton>
              <OrderButton
                label={t.layers.top}
                disabled={!canMoveUp}
                onClick={() => moveLayerTo(layers.length - 1)}
              >
                <ChevronsUp className="w-3.5 h-3.5" />
              </OrderButton>
            </div>
            <div className="text-2xs text-text-muted">
              {t.layers.currentPosition
                .replace(
                  "{current}",
                  currentLayerIndex >= 0 ? String(currentLayerIndex + 1) : "-",
                )
                .replace("{total}", String(layers.length))}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-4 py-3 border-t-[0.5px] border-border/20 shrink-0">
          <button
            onClick={handleReset}
            className="px-3 py-1.5 text-xs text-text-muted hover:text-accent-danger transition-colors"
          >
            {t.layers.resetToSingleColor}
          </button>
          <div className="flex items-center gap-2">
            <button
              onClick={onClose}
              className="px-3 py-1.5 text-xs text-text-secondary hover:text-text-primary bg-bg-secondary rounded-lg transition-colors"
            >
              {t.common.cancel}
            </button>
            <button
              onClick={handleApply}
              className="px-4 py-1.5 text-xs font-medium text-white bg-accent-primary hover:bg-accent-primary/90 rounded-lg transition-colors flex items-center gap-1.5"
            >
              <Check className="w-3.5 h-3.5" />
              {t.common.confirm}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Graduated Controls ─────────────────────────────────────────

interface GraduatedControlsProps {
  t: LayerTranslations;
  numericFields: FieldDescriptor[];
  field: string;
  onFieldChange: (f: string) => void;
  method: ClassificationMethod;
  onMethodChange: (m: ClassificationMethod) => void;
  classes: number;
  onClassesChange: (n: number) => void;
  palette: string[];
  onPaletteChange: (p: string[]) => void;
  breaks: number[];
  selectedRamp: ColorRamp;
  showRampPicker: boolean;
  onToggleRampPicker: () => void;
  onSelectRamp: (id: string) => void;
}

function GraduatedControls({
  t,
  numericFields,
  field,
  onFieldChange,
  method,
  onMethodChange,
  classes,
  onClassesChange,
  palette,
  onPaletteChange,
  breaks,
  selectedRamp,
  showRampPicker,
  onToggleRampPicker,
  onSelectRamp,
}: GraduatedControlsProps) {
  return (
    <>
      {/* Field selector */}
      <ControlRow label={t.valueField}>
        <select
          value={field}
          onChange={(e) => onFieldChange(e.target.value)}
          className="flex-1 bg-bg-secondary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
        >
          {numericFields.length === 0 && (
            <option value="">{t.noNumericFields}</option>
          )}
          {numericFields.map((f) => (
            <option key={f.name} value={f.name}>
              {f.name}
            </option>
          ))}
        </select>
      </ControlRow>

      {/* Method */}
      <ControlRow label={t.method}>
        <select
          value={method}
          onChange={(e) =>
            onMethodChange(e.target.value as ClassificationMethod)
          }
          className="flex-1 bg-bg-secondary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
        >
          <option value="quantile">{t.quantile}</option>
          <option value="equal-interval">{t.equalInterval}</option>
          <option value="jenks">{t.naturalBreaks}</option>
        </select>
      </ControlRow>

      {/* Classes */}
      <ControlRow label={t.classes}>
        <div className="flex items-center gap-2 flex-1">
          <input
            type="range"
            min={2}
            max={10}
            step={1}
            value={classes}
            onChange={(e) => onClassesChange(parseInt(e.target.value))}
            className="flex-1 h-1 accent-accent-primary cursor-pointer"
          />
          <span className="text-xs text-text-primary font-mono w-6 text-center tabular-nums">
            {classes}
          </span>
        </div>
      </ControlRow>

      {/* Color ramp */}
      <ControlRow label={t.colorRamp}>
        <div className="flex-1 relative">
          <button
            onClick={onToggleRampPicker}
            className="w-full flex items-center gap-2 px-2 py-1.5 bg-bg-secondary border-[0.5px] border-border/35 rounded-lg hover:border-accent-primary/60 transition-colors"
          >
            <RampPreview
              colors={selectedRamp.colors(classes)}
              className="flex-1 h-4 rounded"
            />
            <ChevronDown className="w-3 h-3 text-text-muted shrink-0" />
          </button>
          {showRampPicker && (
            <div
              className="absolute left-0 right-0 top-full mt-1 bg-bg-primary border-[0.5px] border-border/35 rounded-lg shadow-xl z-50 max-h-48 overflow-y-auto"
              style={{ scrollbarWidth: "thin" }}
            >
              {COLOR_RAMPS.map((ramp) => (
                <button
                  key={ramp.id}
                  onClick={() => onSelectRamp(ramp.id)}
                  className={`w-full flex items-center gap-2 px-2 py-1.5 hover:bg-bg-hover transition-colors ${
                    ramp.id === selectedRamp.id ? "bg-accent-primary/10" : ""
                  }`}
                >
                  <RampPreview
                    colors={ramp.colors(classes)}
                    className="flex-1 h-3.5 rounded-sm"
                  />
                  <span className="text-2xs text-text-muted w-20 text-right shrink-0 truncate">
                    {ramp.name}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      </ControlRow>

      {/* Legend preview */}
      {field && breaks.length > 0 && (
        <div className="mt-2">
          <div className="text-2xs text-text-muted font-semibold uppercase tracking-wider mb-2">
            {t.classification}
          </div>
          <div className="space-y-0.5">
            {Array.from({ length: breaks.length + 1 }, (_, i) => {
              const color =
                palette[i] || palette[palette.length - 1] || "#9ca3af";
              const lo = i === 0 ? "−∞" : breaks[i - 1].toFixed(2);
              const hi = i < breaks.length ? breaks[i].toFixed(2) : "+∞";
              return (
                <div key={i} className="flex items-center gap-2">
                  <label
                    className="relative w-5 h-4 rounded-sm border-[0.5px] border-border/35 shrink-0 cursor-pointer overflow-hidden"
                    style={{ backgroundColor: color }}
                  >
                    <input
                      type="color"
                      value={color}
                      onChange={(e) => {
                        const newPalette = [...palette];
                        newPalette[i] = e.target.value;
                        onPaletteChange(newPalette);
                      }}
                      className="absolute inset-0 opacity-0 cursor-pointer"
                    />
                  </label>
                  <span className="text-2xs text-text-secondary font-mono tabular-nums">
                    {lo} – {hi}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </>
  );
}

// ─── Categorized Controls ───────────────────────────────────────

interface CategorizedControlsProps {
  t: LayerTranslations;
  allFields: FieldDescriptor[];
  field: string;
  onFieldChange: (f: string) => void;
  maxCategories: number;
  onMaxCategoriesChange: (n: number) => void;
  uniqueValues: string[];
  colors: Record<string, string>;
  onColorsChange: (c: Record<string, string>) => void;
  selectedRamp: ColorRamp;
  showRampPicker: boolean;
  onToggleRampPicker: () => void;
  onSelectRamp: (id: string) => void;
}

function CategorizedControls({
  t,
  allFields,
  field,
  onFieldChange,
  maxCategories,
  onMaxCategoriesChange,
  uniqueValues,
  colors,
  onColorsChange,
  selectedRamp,
  showRampPicker,
  onToggleRampPicker,
  onSelectRamp,
}: CategorizedControlsProps) {
  return (
    <>
      {/* Field selector */}
      <ControlRow label={t.categoryField}>
        <select
          value={field}
          onChange={(e) => onFieldChange(e.target.value)}
          className="flex-1 bg-bg-secondary text-xs text-text-primary px-2 py-1.5 rounded-lg border-[0.5px] border-border/35 focus:border-accent-primary/60 outline-none"
        >
          {allFields.length === 0 && <option value="">{t.noFields}</option>}
          {allFields.map((f) => (
            <option key={f.name} value={f.name}>
              {f.name} ({f.type})
            </option>
          ))}
        </select>
      </ControlRow>

      {/* Max categories */}
      <ControlRow label={t.maxCategories}>
        <div className="flex items-center gap-2 flex-1">
          <input
            type="range"
            min={2}
            max={20}
            step={1}
            value={maxCategories}
            onChange={(e) => onMaxCategoriesChange(parseInt(e.target.value))}
            className="flex-1 h-1 accent-accent-primary cursor-pointer"
          />
          <span className="text-xs text-text-primary font-mono w-6 text-center tabular-nums">
            {maxCategories}
          </span>
        </div>
      </ControlRow>

      {/* Color ramp */}
      <ControlRow label={t.colorRamp}>
        <div className="flex-1 relative">
          <button
            onClick={onToggleRampPicker}
            className="w-full flex items-center gap-2 px-2 py-1.5 bg-bg-secondary border-[0.5px] border-border/35 rounded-lg hover:border-accent-primary/60 transition-colors"
          >
            <RampPreview
              colors={selectedRamp.colors(
                Math.min(uniqueValues.length || maxCategories, 12),
              )}
              className="flex-1 h-4 rounded"
            />
            <ChevronDown className="w-3 h-3 text-text-muted shrink-0" />
          </button>
          {showRampPicker && (
            <div
              className="absolute left-0 right-0 top-full mt-1 bg-bg-primary border-[0.5px] border-border/35 rounded-lg shadow-xl z-50 max-h-48 overflow-y-auto"
              style={{ scrollbarWidth: "thin" }}
            >
              {COLOR_RAMPS.map((ramp) => (
                <button
                  key={ramp.id}
                  onClick={() => onSelectRamp(ramp.id)}
                  className={`w-full flex items-center gap-2 px-2 py-1.5 hover:bg-bg-hover transition-colors ${
                    ramp.id === selectedRamp.id ? "bg-accent-primary/10" : ""
                  }`}
                >
                  <RampPreview
                    colors={ramp.colors(
                      Math.min(uniqueValues.length || maxCategories, 12),
                    )}
                    className="flex-1 h-3.5 rounded-sm"
                  />
                  <span className="text-2xs text-text-muted w-20 text-right shrink-0 truncate">
                    {ramp.name}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      </ControlRow>

      {/* Category legend */}
      {field && uniqueValues.length > 0 && (
        <div className="mt-2">
          <div className="text-2xs text-text-muted font-semibold uppercase tracking-wider mb-2">
            {t.categorized} ({uniqueValues.length})
          </div>
          <div
            className="space-y-0.5 max-h-48 overflow-y-auto"
            style={{ scrollbarWidth: "thin" }}
          >
            {uniqueValues.map((val) => (
              <div key={val} className="flex items-center gap-2">
                <label
                  className="relative w-5 h-4 rounded-sm border-[0.5px] border-border/35 shrink-0 cursor-pointer overflow-hidden"
                  style={{ backgroundColor: colors[val] || "#9ca3af" }}
                >
                  <input
                    type="color"
                    value={colors[val] || "#9ca3af"}
                    onChange={(e) => {
                      onColorsChange({ ...colors, [val]: e.target.value });
                    }}
                    className="absolute inset-0 opacity-0 cursor-pointer"
                  />
                </label>
                <span
                  className="text-2xs text-text-secondary truncate"
                  title={val}
                >
                  {val}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

// ─── Visual Variables ───────────────────────────────────────────
