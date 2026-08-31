/** 文件职责：settings 前端功能：实现该文件名所对应的单一职责。 */
import { AlertCircle, CheckCircle2, Loader2, RotateCcw } from "lucide-react";
import { useT } from "@/app/i18n";
import { SettingItem, SettingSection } from "../components/SettingItem";

export type BackendStatus = "stopped" | "starting" | "ready" | "error";

interface BackendSettingsSectionProps {
  status: BackendStatus;
  error: string;
  restarting: boolean;
  onRestart: () => void;
  sectionRef: (element: HTMLDivElement | null) => void;
}

export function BackendSettingsSection({
  status,
  error,
  restarting,
  onRestart,
  sectionRef,
}: BackendSettingsSectionProps) {
  const t = useT();

  const statusView = {
    ready: (
      <>
        <CheckCircle2 className="w-3.5 h-3.5 text-accent-success" />
        <span className="text-sm text-accent-success">
          {t.settings.statusReady}
        </span>
      </>
    ),
    starting: (
      <>
        <Loader2 className="w-3.5 h-3.5 text-accent-warning animate-spin" />
        <span className="text-sm text-accent-warning">
          {t.settings.statusStarting}
        </span>
      </>
    ),
    stopped: (
      <>
        <div className="w-2 h-2 rounded-full bg-text-muted" />
        <span className="text-sm text-text-muted">
          {t.settings.statusStopped}
        </span>
      </>
    ),
    error: (
      <>
        <AlertCircle className="w-3.5 h-3.5 text-accent-danger" />
        <span className="text-sm text-accent-danger">
          {t.settings.statusError}
        </span>
      </>
    ),
  }[status];

  return (
    <div id="section-backend" ref={sectionRef}>
      <SettingSection title={t.settings.backendRuntime}>
        <SettingItem
          id="backend-status"
          label={t.settings.backendStatus}
          description={t.settings.backendStatusDesc}
        >
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5">{statusView}</div>
            <button
              onClick={onRestart}
              disabled={restarting || status === "starting"}
              className="h-[30px] px-3 text-sm font-medium rounded bg-accent-primary/15 text-accent-primary hover:bg-accent-primary/25 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
            >
              {restarting ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <RotateCcw className="w-3.5 h-3.5" />
              )}
              {restarting ? t.settings.restarting : t.settings.restart}
            </button>
          </div>
        </SettingItem>
        {status === "error" && error && (
          <div className="ml-0 mb-2 px-3 py-2 rounded bg-accent-danger/10 text-xs text-accent-danger break-all">
            {error}
          </div>
        )}
      </SettingSection>
    </div>
  );
}
