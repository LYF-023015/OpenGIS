/** 文件职责：layers 前端功能：页面级界面与交互编排。 */
import { Palette } from "lucide-react";
import { useT } from "@/app/i18n";
import type {
  LayerStyle,
  MapLayerDefinition,
  RasterStyleSettings,
} from "@/shared/geo";
import { RasterStyleEditor } from "../raster-style/RasterStyleEditor";
import {
  ColorSwatch,
  HexInput,
  StyleRow,
  StyleStateChip,
} from "./styleControls";
// ─── Style Panel ────────────────────────────────────────────────

interface LayerStylePanelProps {
  layer: MapLayerDefinition;
  isPointLayer: boolean;
  isFillLayer: boolean;
  onStyleChange: (updates: Partial<LayerStyle>) => void;
  onOpacityChange: (v: number) => void;
  onRasterStyleChange: (raster: RasterStyleSettings) => void;
}

/**
 * Fine-grained paint controls: fill color, stroke color, stroke width,
 * point radius, and opacity. Every input feeds straight into the store,
 * which MapView's layer-sync effect then pushes down to MapLibre via
 * `mapEngine.updateLayerPaint`. That's why we don't need any local
 * "apply" button — the preview is literally the live state.
 *
 * Render-type matrix (see `services/geo/types.ts`):
 *   fill   → polygon layers; show fill color + stroke color + stroke width
 *   circle → point layers;   show fill color + stroke color + stroke width + radius
 *   line   → polyline layers; show color + stroke width (fill color is reused as line color)
 *   raster → no styling controls today (will come with the raster sprint)
 */
export function LayerStylePanel({
  layer,
  isPointLayer,
  isFillLayer,
  onStyleChange,
  onOpacityChange,
  onRasterStyleChange,
}: LayerStylePanelProps) {
  const t = useT();
  const { style } = layer;
  const isRaster = style.renderType === "raster";
  const rasterStyle =
    style.raster ??
    (layer.data.kind === "raster" ? layer.data.rasterStyle : undefined) ??
    {};
  const canRerenderRaster =
    layer.data.kind === "raster" && !!layer.data.rerenderable;

  // Fill-opacity distinct-from-opacity handling: for fills MapLibre reads
  // `fill-opacity`, for everything else `opacity`. We expose a single
  // "Opacity" slider that writes to `style.opacity` (via mapStore's
  // setLayerOpacity), and a separate "Fill α" slider only for polygons.
  const fillOpacity = style.fillOpacity ?? style.opacity;

  return (
    <div className="space-y-1.5">
      {/* Header */}
      <div className="flex items-center gap-1.5 text-2xs text-text-muted mt-0.5 mb-1">
        <Palette className="w-3 h-3" />
        <span>{t.layers.style}</span>
      </div>

      {(style.sizeVariable?.field ||
        style.opacityVariable?.field ||
        style.sortVariable?.field) && (
        <div className="flex flex-wrap gap-1 mb-2">
          {style.sizeVariable?.field && (
            <StyleStateChip label="大小" value={style.sizeVariable.field} />
          )}
          {style.opacityVariable?.field && (
            <StyleStateChip
              label="透明度"
              value={style.opacityVariable.field}
            />
          )}
          {style.sortVariable?.field && (
            <StyleStateChip
              label="顺序"
              value={`${style.sortVariable.field} ${style.sortVariable.order === "ascending" ? "低值在上" : "高值在上"}`}
            />
          )}
        </div>
      )}

      {/* Main color (fill for polygons/points, line for polylines) */}
      {isRaster && (
        <RasterStyleEditor
          layer={layer}
          rasterStyle={rasterStyle}
          disabled={!canRerenderRaster}
          onApply={onRasterStyleChange}
        />
      )}

      {!isRaster && (
        <StyleRow
          label={isFillLayer || isPointLayer ? t.layers.fill : t.layers.color}
        >
          <ColorSwatch
            color={style.color}
            onChange={(color) => onStyleChange({ color })}
          />
          <HexInput
            value={style.color}
            onChange={(color) => onStyleChange({ color })}
          />
        </StyleRow>
      )}

      {/* Stroke color — fills + circles */}
      {(isFillLayer || isPointLayer) && (
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

      {/* Stroke width (always unless raster) */}
      {!isRaster && (
        <StyleRow label={t.layers.width}>
          <input
            type="range"
            min={0}
            max={isPointLayer ? 5 : 10}
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
      )}

      {/* Point radius — circle only */}
      {isPointLayer && (
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

      {/* Icon selector — hidden for now, uncomment when ready
      {isPointLayer && (
        <details className="group/icon">
          ...
        </details>
      )}
      */}

      {/* Fill-opacity — fills only (separate from layer opacity) */}
      {isFillLayer && (
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

      {/* Global opacity (applies to everything) */}
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
    </div>
  );
}
