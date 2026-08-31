/** 文件职责：前端应用装配：React 应用装配入口。 */
import { ApprovalGate } from "@/plugins/assistant/AssistantPlugin";
import { DialogHost } from "@/shared/ui/Dialog/DialogHost";
import { MainLayout } from "@/shell/MainLayout";
import { useApplicationBootstrap } from "./useApplicationBootstrap";

export default function App() {
  useApplicationBootstrap();

  return (
    <>
      <MainLayout />
      <ApprovalGate />
      <DialogHost />
    </>
  );
}
