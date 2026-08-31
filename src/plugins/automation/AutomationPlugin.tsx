/** 文件职责：Automation 能力插件唯一入口，注册 Workflow、Worker 与运行记录能力。 */
import { Activity, GitBranch, ListRestart } from "lucide-react";
import type { RendererPlugin } from "@/app/plugins/runtime";
import {
  UI_CONTRIBUTIONS_SERVICE,
  type SidebarContribution,
  type UiContributionRegistry,
} from "@/app/plugins/uiContributions";
import { RunsPanel } from "./runs/RunsPanel";
import { WorkersPanel } from "./workers/WorkersPanel";
import { WorkflowsPanel } from "./workflows/WorkflowsPanel";

export { WorkflowEditorView } from "./workflows/editor/WorkflowEditorView";

const contributions: SidebarContribution[] = [
  {
    id: "workers",
    order: 40,
    icon: Activity,
    label: (t) => t.sidebar.workers,
    surface: "main",
    render: (context) => (
      <WorkersPanel onOpenScriptTab={context.openMapWorkspace} />
    ),
  },
  {
    id: "workflows",
    order: 60,
    icon: GitBranch,
    label: (t) => t.sidebar.workflows,
    surface: "panel",
    render: () => <WorkflowsPanel />,
  },
  {
    id: "runs",
    order: 70,
    icon: ListRestart,
    label: (t) => t.sidebar.runs,
    surface: "panel",
    render: () => <RunsPanel />,
  },
];

export const automationPlugin: RendererPlugin = {
  descriptor: {
    id: "automation",
    version: "1.0.0",
    requires: ["rpc-bridge", "workspace"],
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
