/** 文件职责：Workspace 能力插件唯一入口，注册文件入口并公开查看器与脚本界面。 */
import { FolderOpen } from "lucide-react";
import type { RendererPlugin } from "@/app/plugins/runtime";
import {
  UI_CONTRIBUTIONS_SERVICE,
  type SidebarContribution,
  type UiContributionRegistry,
} from "@/app/plugins/uiContributions";
import { AssetExplorer } from "./assets/AssetExplorer";

export { CodeTabHeader, CodeViewer } from "./viewers/CodeViewer";
export { CsvTableView } from "./viewers/CsvTableView";
export { ImageViewer } from "./viewers/ImageViewer";
export { ScriptRunnerView } from "./scripts/ScriptRunnerView";

const contributions: SidebarContribution[] = [
  {
    id: "files",
    order: 10,
    icon: FolderOpen,
    label: (t) => t.sidebar.files,
    surface: "panel",
    render: () => <AssetExplorer />,
  },
];

export const workspacePlugin: RendererPlugin = {
  descriptor: {
    id: "workspace",
    version: "1.0.0",
    requires: ["rpc-bridge"],
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
