/** 文件职责：System 能力插件唯一入口，注册设置以及 Tool/Skill 目录。 */
import { Settings, Wrench } from "lucide-react";
import type { RendererPlugin } from "@/app/plugins/runtime";
import {
  UI_CONTRIBUTIONS_SERVICE,
  type SidebarContribution,
  type UiContributionRegistry,
} from "@/app/plugins/uiContributions";
import { ToolAndSkillPanel } from "./catalog/ToolAndSkillPanel";
import { SettingsView } from "./settings/SettingsView";

const contributions: SidebarContribution[] = [
  {
    id: "tools",
    order: 80,
    icon: Wrench,
    label: (t) => t.sidebar.tools,
    surface: "panel",
    render: () => <ToolAndSkillPanel />,
  },
  {
    id: "settings",
    order: 90,
    icon: Settings,
    label: (t) => t.sidebar.settings,
    surface: "main",
    render: () => <SettingsView />,
  },
];

export const systemPlugin: RendererPlugin = {
  descriptor: {
    id: "system",
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
