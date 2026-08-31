/** 文件职责：settings 前端功能：可复用界面组件。 */
import type { ElementType } from "react";
import { AlertCircle, CheckCircle2, Loader2, Search, X } from "lucide-react";
import { useT } from "@/app/i18n";

export interface NavSection {
  id: string;
  label: string;
  icon: ElementType;
  keywords: string[];
}

interface SettingsHeaderProps {
  saveStatus: "idle" | "saving" | "saved" | "error";
  searchQuery: string;
  onSearchChange: (query: string) => void;
}

export function SettingsHeader({
  saveStatus,
  searchQuery,
  onSearchChange,
}: SettingsHeaderProps) {
  const t = useT();
  return (
    <div className="shrink-0 border-b border-border">
      <div className="h-9 flex items-center px-5 gap-3">
        <span className="text-sm font-medium text-text-primary">
          {t.settings.title}
        </span>
        <div className="flex-1" />
        <div className="flex items-center gap-1.5 text-xs">
          {saveStatus === "saving" && (
            <>
              <Loader2 className="w-3 h-3 text-text-muted animate-spin" />
              <span className="text-text-muted">{t.settings.saving}</span>
            </>
          )}
          {saveStatus === "saved" && (
            <>
              <CheckCircle2 className="w-3 h-3 text-accent-success" />
              <span className="text-accent-success">{t.settings.saved}</span>
            </>
          )}
          {saveStatus === "error" && (
            <>
              <AlertCircle className="w-3 h-3 text-accent-danger" />
              <span className="text-accent-danger">
                {t.settings.saveFailed}
              </span>
            </>
          )}
        </div>
      </div>
      <div className="px-5 pb-3">
        <div className="relative max-w-[600px]">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-text-muted" />
          <input
            type="text"
            value={searchQuery}
            onChange={(event) => onSearchChange(event.target.value)}
            placeholder={t.settings.searchPlaceholder}
            className="w-full h-[30px] pl-8 pr-8 text-sm bg-bg-tertiary text-text-primary border border-border rounded-md outline-none focus:border-accent-primary placeholder:text-text-muted transition-colors"
            spellCheck={false}
          />
          {searchQuery && (
            <button
              onClick={() => onSearchChange("")}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-0.5 text-text-muted hover:text-text-secondary transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

interface SettingsNavigationProps {
  sections: NavSection[];
  activeSection: string;
  onSelect: (sectionId: string) => void;
}

export function SettingsNavigation({
  sections,
  activeSection,
  onSelect,
}: SettingsNavigationProps) {
  return (
    <nav className="w-[180px] shrink-0 border-r border-border overflow-y-auto py-3 px-2">
      {sections.map((section) => {
        const active = activeSection === section.id;
        const Icon = section.icon;
        return (
          <button
            key={section.id}
            onClick={() => onSelect(section.id)}
            className={`w-full flex items-center gap-2.5 px-2.5 py-2 rounded-md text-left transition-all duration-100 group relative ${active ? "bg-accent-primary/10 text-accent-primary" : "text-text-secondary hover:text-text-primary hover:bg-bg-hover"}`}
          >
            {active && (
              <div className="absolute left-0 top-1/2 -translate-y-1/2 w-[2px] h-4 bg-accent-primary rounded-r-full" />
            )}
            <Icon className="w-4 h-4 shrink-0" strokeWidth={active ? 2 : 1.5} />
            <span className="text-[13px] font-medium">{section.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
