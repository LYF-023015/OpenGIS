/** 文件职责：GIS 能力插件唯一入口，注册地图、图层、分析、制图与 Operation 能力。 */
import { Layers, LayoutTemplate, PackageOpen } from "lucide-react";
import type { RendererPlugin } from "@/app/plugins/runtime";
import {
  UI_CONTRIBUTIONS_SERVICE,
  type SidebarContribution,
  type UiContributionRegistry,
} from "@/app/plugins/uiContributions";
import { LayerPanel } from "./layers/LayerPanel";
import { LayoutComposerView } from "./layout/LayoutComposerView";
import { captureCurrentMapSnapshot } from "./layout/export/layoutExport";
import { useLayoutComposerStore } from "./layout/model/layoutComposerStore";
import { OperationsPanel } from "./operations/OperationsPanel";

export { DataPivotPanel } from "./analysis/DataPivotPanel";
export { MapView } from "./map/MapView";
export { mapEngine } from "./map/engine/MapEngine";
export { useMapStore } from "./map/model/mapStore";
export { OperationEditorView } from "./operations/editor/OperationEditorView";

const contributions: SidebarContribution[] = [
  {
    id: "layers",
    order: 20,
    icon: Layers,
    label: (t) => t.sidebar.layers,
    surface: "panel",
    render: () => <LayerPanel />,
  },
  {
    id: "canvas",
    order: 30,
    icon: LayoutTemplate,
    label: (t) => t.sidebar.canvas,
    surface: "primary",
    render: () => <LayoutComposerView />,
    onActivate: () => {
      const snapshot = captureCurrentMapSnapshot();
      if (snapshot) {
        useLayoutComposerStore.getState().setMapSnapshotUrl(snapshot);
      }
    },
  },
  {
    id: "operations",
    order: 50,
    icon: PackageOpen,
    label: (t) => t.sidebar.operations,
    surface: "panel",
    render: () => <OperationsPanel />,
  },
];

export const gisPlugin: RendererPlugin = {
  descriptor: {
    id: "gis",
    version: "1.0.0",
    requires: ["rpc-bridge", "map-extensions"],
  },
  activate: (context) => {
    const registry = context.require<UiContributionRegistry>(
      UI_CONTRIBUTIONS_SERVICE,
    );
    const disposers = contributions.map((item) =>
      registry.contributeSidebar(item),
    );
    return () => [...disposers].reverse().forEach((dispose) => dispose());
  },
};
