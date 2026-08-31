/** 文件职责：layers 前端功能：可复用界面组件。 */
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowLeftRight,
  BarChart3,
  ChevronDown,
  ChevronRight,
  Eye,
  EyeOff,
  GripVertical,
  Maximize2,
  Trash2,
} from "lucide-react";
import { useT } from "@/app/i18n";
import { useMapStore } from "@/plugins/gis/map/model/mapStore";
import { getCategorizedCache } from "@/plugins/gis/map/renderers/categorizedRenderer";
import { usePivotStore } from "@/plugins/gis/analysis/model/types";
import { targetFromLayer } from "@/plugins/gis/analysis/model/types";
import type { LayerStyle, MapLayerDefinition } from "@/shared/geo";
import { GraduatedStylePanel } from "../vector-style/GraduatedStylePanel";
import { LayerIcon } from "./LayerIcon";
import { LayerStylePanel } from "../style/LayerStylePanel";
import { rerenderRasterLayer } from "../raster-style/rasterStyleService";
import {
  ColorSwatch,
  HexInput,
  StyleRow,
  StyleStateChip,
} from "../style/styleControls";

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
// ─── Layer Item ─────────────────────────────────────────────────

interface LayerItemProps {
  layer: MapLayerDefinition;
  isActive: boolean;
  isDragging: boolean;
  isDragOver: boolean;
  searchQuery: string;
  onSelect: () => void;
  onToggleVisibility: () => void;
  onRemove: () => void;
  onZoomTo: () => void;
  onDragStart: () => void;
  onDragEnd: () => void;
  onDragEnterLayer: () => void;
  onDropLayer: () => void;
}

export function LayerItem({
  layer,
  isActive,
  isDragging,
  isDragOver,
  searchQuery,
  onSelect,
  onToggleVisibility,
  onRemove,
  onZoomTo,
  onDragStart,
  onDragEnd,
  onDragEnterLayer,
  onDropLayer,
}: LayerItemProps) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);
  const [showClassification, setShowClassification] = useState(false);
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
  } | null>(null);
  const setLayerOpacity = useMapStore((s) => s.setLayerOpacity);
  const updateLayerStyle = useMapStore((s) => s.updateLayerStyle);
  const addLayer = useMapStore((s) => s.addLayer);
  const renameLayer = useMapStore((s) => s.renameLayer);
  const openPivot = usePivotStore((s) => s.open);
  const [isRenaming, setIsRenaming] = useState(false);
  const [renameValue, setRenameValue] = useState("");
  const renameInputRef = useRef<HTMLInputElement>(null);

  const geometryType =
    layer.data.kind === "vector" ? layer.data.geometryType : "Raster";
  // Determine geometry-based type (independent of renderType, so graduated/categorized still know the geometry)
  const isPointGeom =
    layer.data.kind === "vector" &&
    (layer.data.geometryType === "Point" ||
      layer.data.geometryType === "MultiPoint");
  const isFillGeom =
    layer.data.kind === "vector" &&
    (layer.data.geometryType === "Polygon" ||
      layer.data.geometryType === "MultiPolygon");
  const isClassified =
    layer.style.renderType === "graduated" ||
    layer.style.renderType === "categorized";
  // Geometry flags consumed by style controls.
  const isPointLayer =
    layer.style.renderType === "circle" || layer.style.renderType === "symbol";
  const isFillLayer = layer.style.renderType === "fill";

  const handleRowDragStart = useCallback(
    (e: React.DragEvent) => {
      // Use a custom mime type so the panel-level file-drop handler can
      // distinguish internal reorders from OS file drops.
      e.dataTransfer.setData("text/layer-id", layer.id);
      e.dataTransfer.effectAllowed = "move";
      onDragStart();
    },
    [layer.id, onDragStart],
  );

  const handleRowDragOver = useCallback(
    (e: React.DragEvent) => {
      if (!e.dataTransfer.types.includes("text/layer-id")) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      onDragEnterLayer();
    },
    [onDragEnterLayer],
  );

  const handleRowDrop = useCallback(
    (e: React.DragEvent) => {
      if (!e.dataTransfer.types.includes("text/layer-id")) return;
      e.preventDefault();
      e.stopPropagation();
      onDropLayer();
    },
    [onDropLayer],
  );

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      onSelect();
      setContextMenu({ x: e.clientX, y: e.clientY });
    },
    [onSelect],
  );

  return (
    <div
      onDragEnd={onDragEnd}
      onDragOver={handleRowDragOver}
      onDrop={handleRowDrop}
      onContextMenu={handleContextMenu}
      className={`
        group relative mx-1 rounded-md transition-colors duration-100
        ${isActive ? "bg-accent-primary/10" : "hover:bg-bg-hover"}
        ${isDragging ? "opacity-40" : ""}
      `}
    >
      {/* Drop-indicator line (above row when being dragged-over) */}
      {isDragOver && (
        <div className="absolute left-0 right-0 -top-[1px] h-0.5 bg-accent-primary rounded-full z-10 pointer-events-none" />
      )}

      {/* Active indicator */}
      {isActive && (
        <div className="absolute left-0 top-1/2 -translate-y-1/2 w-[2px] h-5 bg-accent-primary rounded-r-full" />
      )}

      {/* Main row */}
      <div
        className="flex items-center gap-1.5 px-2 py-1.5 cursor-pointer"
        onClick={onSelect}
      >
        {/* Drag handle — only this element is draggable, hidden during search */}
        <div
          draggable={!searchQuery}
          onDragStart={handleRowDragStart}
          className={`w-3 h-4 flex items-center justify-center shrink-0 ${
            searchQuery
              ? "text-transparent"
              : "text-text-muted/40 hover:text-text-secondary cursor-grab active:cursor-grabbing"
          }`}
          title={searchQuery ? "" : t.layers.dragToReorder}
        >
          <GripVertical className="w-3 h-3" />
        </div>

        {/* Expand toggle */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            setExpanded(!expanded);
          }}
          className="w-4 h-4 flex items-center justify-center text-text-muted hover:text-text-secondary shrink-0"
        >
          {expanded ? (
            <ChevronDown className="w-3 h-3" />
          ) : (
            <ChevronRight className="w-3 h-3" />
          )}
        </button>

        {/* Visibility toggle */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            onToggleVisibility();
          }}
          className={`w-4 h-4 flex items-center justify-center shrink-0 transition-colors ${
            layer.visible ? "text-text-secondary" : "text-text-muted/40"
          }`}
          title={layer.visible ? t.layers.hideLayer : t.layers.showLayer}
        >
          {layer.visible ? (
            <Eye className="w-3.5 h-3.5" />
          ) : (
            <EyeOff className="w-3.5 h-3.5" />
          )}
        </button>

        {/* Layer icon (color swatch + geometry type) */}
        <LayerIcon
          geometryType={
            layer.data.kind === "vector" ? layer.data.geometryType : undefined
          }
          color={layer.style.color}
          className="shrink-0"
        />

        {/* Layer name (or rename input) */}
        {isRenaming ? (
          <input
            ref={renameInputRef}
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onBlur={() => {
              const trimmed = renameValue.trim();
              if (trimmed && trimmed !== layer.name) {
                renameLayer(layer.id, trimmed);
              }
              setIsRenaming(false);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                const trimmed = renameValue.trim();
                if (trimmed && trimmed !== layer.name) {
                  renameLayer(layer.id, trimmed);
                }
                setIsRenaming(false);
              }
              if (e.key === "Escape") setIsRenaming(false);
            }}
            onClick={(e) => e.stopPropagation()}
            autoFocus
            className="flex-1 min-w-0 text-xs bg-bg-primary border border-accent-primary rounded px-1.5 py-0.5 outline-none text-text-primary"
          />
        ) : (
          <span
            className={`text-xs truncate flex-1 cursor-text ${
              isActive ? "text-text-primary font-medium" : "text-text-secondary"
            } ${!layer.visible ? "opacity-50" : ""}`}
            title={`${layer.name} (double-click to rename)`}
            onDoubleClick={(e) => {
              e.stopPropagation();
              setRenameValue(layer.name);
              setIsRenaming(true);
            }}
          >
            {layer.name}
          </span>
        )}

        {/* Action buttons (visible on hover) */}
        <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onZoomTo();
            }}
            className="w-5 h-5 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
            title={t.layers.zoomToLayer}
          >
            <Maximize2 className="w-3 h-3" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onRemove();
            }}
            className="w-5 h-5 rounded flex items-center justify-center text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 transition-colors"
            title={t.layers.removeLayer}
          >
            <Trash2 className="w-3 h-3" />
          </button>
        </div>
      </div>

      {/* Expanded details */}
      {expanded && (
        <div
          draggable={false}
          className="px-2 pb-2 pl-8 animate-slide-up"
          onClick={(e) => e.stopPropagation()}
          onMouseDown={(e) => e.stopPropagation()}
          onDragStart={(e) => {
            e.preventDefault();
            e.stopPropagation();
          }}
          onPointerDown={(e) => e.stopPropagation()}
        >
          {/* Info row */}
          <div className="flex items-center gap-3 text-2xs text-text-muted mb-2">
            <span>{geometryType}</span>
            <span>·</span>
            <span>{layer.data.crs}</span>
          </div>

          {/* Style panel — switches between single-color and classification summary */}
          {isClassified ? (
            <ClassifiedStyleSummary
              layer={layer}
              isPointGeom={isPointGeom}
              isFillGeom={isFillGeom}
              onStyleChange={(updates) => updateLayerStyle(layer.id, updates)}
              onOpacityChange={(v) => setLayerOpacity(layer.id, v)}
              onEditClassification={() => setShowClassification(true)}
              onResetToSingle={() => {
                let defaultType: LayerStyle["renderType"] = "fill";
                if (layer.data.kind === "vector") {
                  const gt = layer.data.geometryType;
                  if (gt === "Point" || gt === "MultiPoint")
                    defaultType = "circle";
                  else if (gt === "LineString" || gt === "MultiLineString")
                    defaultType = "line";
                }
                updateLayerStyle(layer.id, {
                  renderType: defaultType,
                  graduated: undefined,
                  categorized: undefined,
                });
              }}
            />
          ) : (
            <>
              <LayerStylePanel
                layer={layer}
                isPointLayer={isPointLayer}
                isFillLayer={isFillLayer}
                onStyleChange={(updates) => updateLayerStyle(layer.id, updates)}
                onOpacityChange={(v) => setLayerOpacity(layer.id, v)}
                onRasterStyleChange={(raster) =>
                  rerenderRasterLayer(layer, raster, addLayer)
                }
              />

              {/* Classification button — only for vector layers */}
              {layer.data.kind === "vector" && (
                <button
                  onClick={() => setShowClassification(true)}
                  className="mt-2 w-full flex items-center justify-center gap-1.5 px-2 py-1.5 text-2xs font-medium text-accent-primary bg-accent-primary/10 hover:bg-accent-primary/20 rounded-lg transition-colors"
                >
                  <BarChart3 className="w-3 h-3" />
                  {t.layers.classificationRenderer}
                </button>
              )}
            </>
          )}

          {/* Classification panel (modal) */}
          {showClassification && (
            <GraduatedStylePanel
              layer={layer}
              onClose={() => setShowClassification(false)}
            />
          )}

          {/* Source info */}
          <div
            className="mt-2 text-2xs text-text-muted truncate"
            title={layer.meta.fileName}
          >
            📄 {layer.meta.fileName} ({formatFileSize(layer.meta.fileSize)})
          </div>
        </div>
      )}

      {contextMenu && (
        <LayerContextMenu
          position={contextMenu}
          onClose={() => setContextMenu(null)}
          onOpenPivot={() => {
            setContextMenu(null);
            openPivot(targetFromLayer(layer));
          }}
        />
      )}
    </div>
  );
}

function LayerContextMenu({
  position,
  onClose,
  onOpenPivot,
}: {
  position: { x: number; y: number };
  onClose: () => void;
  onOpenPivot: () => void;
}) {
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleMouseDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node))
        onClose();
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("mousedown", handleMouseDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleMouseDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [onClose]);

  return (
    <div
      ref={menuRef}
      className="fixed z-[9999] bg-bg-secondary border border-border rounded-lg shadow-xl py-1 min-w-[160px] animate-fade-in"
      style={{ left: position.x, top: position.y }}
    >
      <button
        onClick={onOpenPivot}
        className="w-full flex items-center gap-2.5 px-3 py-1.5 text-xs text-accent-geo hover:bg-accent-geo/10 transition-colors"
      >
        <BarChart3 className="w-3.5 h-3.5" />
        <span>数据透视</span>
      </button>
    </div>
  );
}

// ─── Classified Style Summary ───────────────────────────────────

/**
 * Compact summary panel shown when a layer uses graduated/categorized rendering.
 * Displays: legend preview + common paint controls (strokeWidth, radius, opacity)
 * + buttons to edit classification or switch back to single-color.
 */
interface ClassifiedStyleSummaryProps {
  layer: MapLayerDefinition;
  isPointGeom: boolean;
  isFillGeom: boolean;
  onStyleChange: (updates: Partial<LayerStyle>) => void;
  onOpacityChange: (v: number) => void;
  onEditClassification: () => void;
  onResetToSingle: () => void;
}

function ClassifiedStyleSummary({
  layer,
  isPointGeom,
  isFillGeom,
  onStyleChange,
  onOpacityChange,
  onEditClassification,
  onResetToSingle,
}: ClassifiedStyleSummaryProps) {
  const t = useT();
  const { style } = layer;
  const isGraduated = style.renderType === "graduated";
  const fillOpacity = style.fillOpacity ?? style.opacity;

  // Build legend items
  const legendItems: { color: string; label: string }[] = [];
  if (isGraduated && style.graduated) {
    const { breaks = [], palette = [] } = style.graduated;
    for (let i = 0; i <= breaks.length; i++) {
      const color = palette[i] || palette[palette.length - 1] || "#9ca3af";
      const lo = i === 0 ? "−∞" : (breaks[i - 1]?.toFixed(2) ?? "?");
      const hi = i < breaks.length ? (breaks[i]?.toFixed(2) ?? "?") : "+∞";
      legendItems.push({ color, label: `${lo} – ${hi}` });
    }
  } else if (style.renderType === "categorized") {
    // 优先读 store 里的 colors，若 Python RPC 未传 colors 则从 renderer cache 获取
    const colors = style.categorized?.colors ?? getCategorizedCache(layer.id);
    if (colors) {
      for (const [val, color] of Object.entries(colors)) {
        legendItems.push({ color, label: val });
      }
    }
  }

  return (
    <div className="space-y-1.5">
      {/* Header with mode badge */}
      <div className="flex items-center gap-1.5 text-2xs text-text-muted mt-0.5 mb-1">
        <BarChart3 className="w-3 h-3" />
        <span>{isGraduated ? t.layers.graduated : t.layers.categorized}</span>
        <span className="text-text-muted/40">·</span>
        <span className="truncate">
          {isGraduated ? style.graduated?.field : style.categorized?.field}
        </span>
      </div>

      <div className="flex flex-wrap gap-1 mb-2">
        {style.sizeVariable?.field && (
          <StyleStateChip label="大小" value={style.sizeVariable.field} />
        )}
        {style.opacityVariable?.field && (
          <StyleStateChip label="透明度" value={style.opacityVariable.field} />
        )}
        {style.sortVariable?.field && (
          <StyleStateChip
            label="顺序"
            value={`${style.sortVariable.field} ${style.sortVariable.order === "ascending" ? "低值在上" : "高值在上"}`}
          />
        )}
      </div>

      {/* Compact legend preview (max 6 items, then "...more") */}
      {legendItems.length > 0 && (
        <div className="space-y-0.5 mb-2">
          {legendItems.slice(0, 6).map((item, i) => (
            <div key={i} className="flex items-center gap-2">
              <div
                className="w-4 h-3 rounded-sm border border-border shrink-0"
                style={{ backgroundColor: item.color }}
              />
              <span className="text-2xs text-text-secondary font-mono tabular-nums truncate">
                {item.label}
              </span>
            </div>
          ))}
          {legendItems.length > 6 && (
            <div className="text-2xs text-text-muted italic">
              …{legendItems.length - 6} {t.layers.more}
            </div>
          )}
        </div>
      )}

      {/* Common paint controls — always available regardless of classification */}
      {/* Stroke color — fills + points */}
      {(isFillGeom || isPointGeom) && (
        <StyleRow label={t.layers.stroke}>
          <ColorSwatch
            color={style.strokeColor}
            onChange={(strokeColor) => onStyleChange({ strokeColor })}
          />
          <HexInput
            value={style.strokeColor}
            onChange={(strokeColor) => onStyleChange({ strokeColor })}
          />
        </StyleRow>
      )}

      {/* Stroke width */}
      <StyleRow label={t.layers.width}>
        <input
          type="range"
          min={0}
          max={isPointGeom ? 5 : 10}
          step={0.5}
          value={style.strokeWidth}
          onChange={(e) =>
            onStyleChange({ strokeWidth: parseFloat(e.target.value) })
          }
          className="flex-1 h-1 accent-accent-primary cursor-pointer min-w-0"
        />
        <span className="text-2xs text-text-muted w-10 text-right tabular-nums shrink-0">
          {style.strokeWidth.toFixed(1)}
        </span>
      </StyleRow>

      {/* Point radius — point geometry only */}
      {isPointGeom && (
        <StyleRow label={t.layers.radius}>
          <input
            type="range"
            min={1}
            max={30}
            step={0.5}
            value={style.radius ?? 5}
            onChange={(e) =>
              onStyleChange({ radius: parseFloat(e.target.value) })
            }
            className="flex-1 h-1 accent-accent-primary cursor-pointer min-w-0"
          />
          <span className="text-2xs text-text-muted w-10 text-right tabular-nums shrink-0">
            {(style.radius ?? 5).toFixed(1)}
          </span>
        </StyleRow>
      )}

      {/* Fill-opacity — polygon geometry only */}
      {isFillGeom && (
        <StyleRow label={t.layers.fillAlpha}>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={fillOpacity}
            onChange={(e) =>
              onStyleChange({ fillOpacity: parseFloat(e.target.value) })
            }
            className="flex-1 h-1 accent-accent-primary cursor-pointer min-w-0"
          />
          <span className="text-2xs text-text-muted w-10 text-right tabular-nums shrink-0">
            {Math.round(fillOpacity * 100)}%
          </span>
        </StyleRow>
      )}

      {/* Global opacity */}
      <StyleRow label={t.layers.opacity}>
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={style.opacity}
          onChange={(e) => onOpacityChange(parseFloat(e.target.value))}
          className="flex-1 h-1 accent-accent-primary cursor-pointer min-w-0"
        />
        <span className="text-2xs text-text-muted w-10 text-right tabular-nums shrink-0">
          {Math.round(style.opacity * 100)}%
        </span>
      </StyleRow>

      {/* Action buttons */}
      <div className="flex items-center gap-2 mt-2">
        <button
          onClick={onEditClassification}
          className="flex-1 flex items-center justify-center gap-1.5 px-2 py-1.5 text-2xs font-medium text-accent-primary bg-accent-primary/10 hover:bg-accent-primary/20 rounded-lg transition-colors"
        >
          <BarChart3 className="w-3 h-3" />
          {t.layers.editClassification}
        </button>
        <button
          onClick={onResetToSingle}
          className="flex items-center justify-center gap-1 px-2 py-1.5 text-2xs text-text-muted hover:text-text-secondary bg-bg-secondary hover:bg-bg-hover rounded-lg transition-colors"
          title={t.layers.switchToSingle}
        >
          <ArrowLeftRight className="w-3 h-3" />
          {t.layers.single}
        </button>
      </div>
    </div>
  );
}
