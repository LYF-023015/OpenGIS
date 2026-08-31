/** 文件职责：layers 前端功能：页面级界面与交互编排。 */
/**
 * LayerPanel — ArcGIS/QGIS-style layer management sidebar panel.
 *
 * Features:
 * - Layer list with visibility toggles
 * - Active layer highlighting
 * - Layer reordering (drag & drop, HTML5 native)
 * - Layer removal
 * - Opacity / fill color / stroke color / stroke width / point radius controls
 * - Zoom-to-layer
 * - Add data button (file picker + drag & drop)
 */
import { useState, useCallback, useRef } from "react";
import { Trash2, Plus, FileUp, MapPin, Loader2 } from "lucide-react";
import { useT } from "@/app/i18n";
import { useMapStore } from "@/plugins/gis/map/model/mapStore";
import { loadGeoFiles } from "@/shared/geo";
import { mapEngine } from "@/plugins/gis/map/engine/MapEngine";
import { LayerItem } from "./layer/LayerItem";

export function LayerPanel() {
  const t = useT();
  const layers = useMapStore((s) => s.layers);
  const activeLayerId = useMapStore((s) => s.activeLayerId);
  const addLayers = useMapStore((s) => s.addLayers);
  const removeLayer = useMapStore((s) => s.removeLayer);
  const setActiveLayer = useMapStore((s) => s.setActiveLayer);
  const setLayerVisibility = useMapStore((s) => s.setLayerVisibility);
  const clearLayers = useMapStore((s) => s.clearLayers);
  const reorderLayers = useMapStore((s) => s.reorderLayers);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  // id of the layer currently being dragged for reordering; null when idle.
  // We distinguish this from file-system drag by guarding `onDragStart` on
  // the inner handle/row vs the panel-level `onDragOver` for files.
  const [dragLayerId, setDragLayerId] = useState<string | null>(null);
  const [dragOverLayerId, setDragOverLayerId] = useState<string | null>(null);
  const [isLoadingFiles, setIsLoadingFiles] = useState(false);

  // ─── Add Data ─────────────────────────────────────────────────

  const handleAddFiles = useCallback(
    async (files: File[]) => {
      if (files.length === 0) return;
      setIsLoadingFiles(true);
      try {
        const newLayers = await loadGeoFiles(files);
        if (newLayers.length > 0) {
          addLayers(newLayers);
          // Fit to first new layer
          const first = newLayers[0];
          if (first.data.kind === "vector") {
            const { bbox } = first.data;
            mapEngine.fitBounds([bbox.minX, bbox.minY, bbox.maxX, bbox.maxY]);
          } else if (first.data.kind === "raster") {
            const { bbox } = first.data;
            mapEngine.fitBounds([bbox.minX, bbox.minY, bbox.maxX, bbox.maxY]);
          }
        }
      } catch (err) {
        console.error("Failed to load files:", err);
      } finally {
        setIsLoadingFiles(false);
      }
    },
    [addLayers],
  );

  const handleFileInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files || []);
      handleAddFiles(files);
      // Reset input so the same file can be re-selected
      e.target.value = "";
    },
    [handleAddFiles],
  );

  // ─── File-drop (only fires when user drags from OS, not internal DnD) ──

  const handleFileDrop = useCallback(
    (e: React.DragEvent) => {
      // Internal layer reordering sets dataTransfer.types to ['text/layer-id'];
      // a file drop from the OS adds 'Files'. Use that as the gate.
      if (!e.dataTransfer.types.includes("Files")) return;
      e.preventDefault();
      setIsDragOver(false);
      const files = Array.from(e.dataTransfer.files);
      handleAddFiles(files);
    },
    [handleAddFiles],
  );

  const handleFileDragOver = useCallback((e: React.DragEvent) => {
    if (!e.dataTransfer.types.includes("Files")) return;
    e.preventDefault();
    setIsDragOver(true);
  }, []);

  const handleFileDragLeave = useCallback(() => {
    setIsDragOver(false);
  }, []);

  // ─── Layer Reordering (internal DnD) ──────────────────────────

  // The list on screen is rendered top→bottom (top = last in store array),
  // so we need to translate visible-drop-target into store-array index.
  const handleReorderDrop = useCallback(
    (targetLayerId: string) => {
      if (!dragLayerId || dragLayerId === targetLayerId) return;
      const fromIdx = layers.findIndex((l) => l.id === dragLayerId);
      const toIdx = layers.findIndex((l) => l.id === targetLayerId);
      if (fromIdx === -1 || toIdx === -1) return;
      reorderLayers(fromIdx, toIdx);
    },
    [dragLayerId, layers, reorderLayers],
  );

  // ─── Render ───────────────────────────────────────────────────

  return (
    <div
      className="w-full h-full flex flex-col bg-bg-primary overflow-hidden select-none"
      onDrop={handleFileDrop}
      onDragOver={handleFileDragOver}
      onDragLeave={handleFileDragLeave}
    >
      {/* Header */}
      <div className="border-b border-border shrink-0">
        <div className="h-9 flex items-center px-3 gap-2">
          <span className="text-xs font-semibold text-text-secondary flex-1">
            {t.layers.title}
          </span>

          {/* Add data button */}
          <button
            onClick={() => fileInputRef.current?.click()}
            className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
            title={t.layers.addData}
          >
            <Plus className="w-3.5 h-3.5" />
          </button>

          {/* Clear all */}
          {layers.length > 0 && (
            <button
              onClick={clearLayers}
              className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 transition-colors"
              title={t.layers.removeAll}
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          )}

          {/* Hidden file input */}
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept=".geojson,.json,.csv,.tsv,.shp,.dbf,.shx,.prj,.cpg,.kml,.gpkg,.tif,.tiff"
            onChange={handleFileInputChange}
            className="hidden"
          />
        </div>
        {/* Search input */}
        {layers.length > 3 && (
          <div className="px-2 pb-1.5">
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t.layers.searchLayers || "Search layers..."}
              className="w-full h-7 text-xs bg-bg-tertiary border border-border rounded px-2 outline-none focus:border-accent-primary/40 placeholder:text-text-muted/50"
            />
          </div>
        )}
      </div>

      {/* Layer list */}
      <div className="flex-1 overflow-y-auto scrollbar-thin">
        {layers.length === 0 ? (
          <LayerEmptyState isDragOver={isDragOver} />
        ) : (
          <div className="py-1">
            {/* Render in reverse order (top layer first, like GIS convention) */}
            {[...layers]
              .reverse()
              .filter((layer) => {
                if (!searchQuery.trim()) return true;
                const q = searchQuery.toLowerCase();
                return layer.name.toLowerCase().includes(q);
              })
              .map((layer) => (
                <LayerItem
                  key={layer.id}
                  layer={layer}
                  isActive={layer.id === activeLayerId}
                  isDragging={dragLayerId === layer.id}
                  isDragOver={
                    dragOverLayerId === layer.id && dragLayerId !== layer.id
                  }
                  searchQuery={searchQuery}
                  onSelect={() => setActiveLayer(layer.id)}
                  onToggleVisibility={() =>
                    setLayerVisibility(layer.id, !layer.visible)
                  }
                  onRemove={() => removeLayer(layer.id)}
                  onZoomTo={() => {
                    if (layer.data.kind === "vector") {
                      const { bbox } = layer.data;
                      mapEngine.fitBounds([
                        bbox.minX,
                        bbox.minY,
                        bbox.maxX,
                        bbox.maxY,
                      ]);
                    }
                  }}
                  onDragStart={
                    searchQuery ? () => {} : () => setDragLayerId(layer.id)
                  }
                  onDragEnd={() => {
                    setDragLayerId(null);
                    setDragOverLayerId(null);
                  }}
                  onDragEnterLayer={() => setDragOverLayerId(layer.id)}
                  onDropLayer={() => {
                    handleReorderDrop(layer.id);
                    setDragLayerId(null);
                    setDragOverLayerId(null);
                  }}
                />
              ))}
          </div>
        )}
      </div>

      {/* Loading overlay */}
      {isLoadingFiles && (
        <div className="absolute inset-0 bg-bg-primary/60 backdrop-blur-sm flex items-center justify-center z-30">
          <div className="flex flex-col items-center gap-2">
            <Loader2 className="w-6 h-6 text-accent-primary animate-spin" />
            <p className="text-xs text-text-secondary">
              {t.layers.loadingLayers}
            </p>
          </div>
        </div>
      )}

      {/* File drop overlay */}
      {isDragOver && (
        <div className="absolute inset-0 bg-accent-primary/10 border-2 border-dashed border-accent-primary rounded-lg flex items-center justify-center z-20 pointer-events-none">
          <div className="text-center">
            <FileUp className="w-8 h-8 text-accent-primary mx-auto mb-2" />
            <p className="text-sm font-medium text-accent-primary">
              {t.layers.dropFiles}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Empty State ────────────────────────────────────────────────

function LayerEmptyState({ isDragOver }: { isDragOver: boolean }) {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-4">
      <div className="text-center">
        <div className="w-10 h-10 rounded-xl bg-accent-geo/10 flex items-center justify-center mx-auto mb-3">
          <MapPin className="w-5 h-5 text-accent-geo/50" />
        </div>
        <p className="text-xs text-text-muted mb-1">{t.layers.noLayers}</p>
        <p className="text-2xs text-text-muted/70">{t.layers.noLayersHint}</p>
        {isDragOver && (
          <p className="text-2xs text-accent-primary mt-2">
            {t.layers.dropToAdd}
          </p>
        )}
      </div>
    </div>
  );
}
