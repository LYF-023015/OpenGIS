/** 文件职责：Assistant 能力插件唯一入口，公开聊天与审批界面并声明插件生命周期。 */
import type { RendererPlugin } from "@/app/plugins/runtime";

export { ApprovalGate } from "./approval/ApprovalGate";
export { ChatView } from "./chat/ChatView";

export const assistantPlugin: RendererPlugin = {
  descriptor: {
    id: "assistant",
    version: "1.0.0",
    requires: ["rpc-bridge"],
  },
  activate: () => () => {},
};
