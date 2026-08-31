/** 文件职责：layers 前端功能：承载该领域的核心业务流程。 */
import { parseGeoTIFF } from "@/shared/geo/parsers/geotiffParser";
import { getRasterBuffer } from "@/shared/geo/layerDataRegistry";
import type { MapLayerDefinition, RasterStyleSettings } from "@/shared/geo";

export async function rerenderRasterLayer(
  layer: MapLayerDefinition,
  rasterStyle: RasterStyleSettings,
  addLayer: (layer: MapLayerDefinition) => void,
): Promise<void> {
  if (layer.data.kind !== "raster") return;
  if (layer.data.rasterId && layer.data.tileUrl) {
    await updateBackendTileRasterLayer(layer, rasterStyle, addLayer);
    return;
  }
  const sourcePath = layer.data.sourcePath ?? layer.meta.filePath;
  if (!layer.data.rerenderable) return;
  const api = window.electronAPI;
  try {
    let buffer: ArrayBuffer | undefined;
    if (sourcePath && api?.readFileAsBuffer) {
      const result = (await api.readFileAsBuffer(sourcePath)) as {
        success?: boolean;
        ok?: boolean;
        buffer?: ArrayBuffer;
        error?: string;
      };
      const ok = result?.ok ?? result?.success ?? false;
      if (!ok || !result.buffer)
        throw new Error(result?.error || "readFileAsBuffer returned no buffer");
      buffer =
        result.buffer instanceof ArrayBuffer
          ? result.buffer
          : new Uint8Array(result.buffer).buffer;
    } else {
      buffer = getRasterBuffer(layer.data.sourceBufferId);
    }
    if (!buffer)
      throw new Error("No original TIFF source is available for re-rendering");
    const fileName =
      sourcePath?.split(/[\\/]/).pop() ?? layer.meta.fileName ?? "raster.tif";
    const raster = await parseGeoTIFF(buffer, fileName, {
      sourcePath,
      sourceBufferId: layer.data.sourceBufferId,
      rasterStyle,
    });
    addLayer({
      ...layer,
      data: raster,
      style: {
        ...layer.style,
        opacity: rasterStyle.opacity ?? layer.style.opacity,
        raster: raster.rasterStyle,
      },
    });
  } catch (error) {
    console.error("[LayerPanel] failed to re-render raster layer:", error);
  }
}

async function updateBackendTileRasterLayer(
  layer: MapLayerDefinition,
  rasterStyle: RasterStyleSettings,
  addLayer: (layer: MapLayerDefinition) => void,
): Promise<void> {
  if (
    layer.data.kind !== "raster" ||
    !layer.data.rasterId ||
    !layer.data.tileUrl
  )
    return;
  try {
    const url = new URL(layer.data.tileUrl);
    const response = await fetch(
      `${url.origin}/api/rasters/${encodeURIComponent(layer.data.rasterId)}/style`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(rasterStyle),
      },
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const payload = await response.json();
    url.searchParams.set(
      "rev",
      String(
        typeof payload?.style_revision === "number"
          ? payload.style_revision
          : Date.now(),
      ),
    );
    addLayer({
      ...layer,
      data: {
        ...layer.data,
        tileUrl: url.toString(),
        rasterStyle: payload?.style ?? rasterStyle,
        rerenderable: true,
      },
      style: {
        ...layer.style,
        opacity: rasterStyle.opacity ?? layer.style.opacity,
        raster: payload?.style ?? rasterStyle,
      },
    });
  } catch (error) {
    console.error("[LayerPanel] failed to update backend raster style:", error);
  }
}
