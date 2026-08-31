/** 文件职责：前端应用装配：实现该文件名所对应的单一职责。 */
import { useSyncExternalStore, type ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import type { TranslationKeys } from "@/app/i18n";

export const UI_CONTRIBUTIONS_SERVICE = "ui.contributions";

export interface UiRenderContext {
  openMapWorkspace(): void;
}

export interface SidebarContribution {
  id: string;
  order: number;
  icon: LucideIcon;
  label(t: TranslationKeys): string;
  surface: "panel" | "main" | "primary";
  render(context: UiRenderContext): ReactNode;
  onActivate?(): void;
}

export class UiContributionRegistry {
  private readonly sidebar = new Map<string, SidebarContribution>();
  private readonly listeners = new Set<() => void>();
  private snapshot: readonly SidebarContribution[] = [];

  contributeSidebar(contribution: SidebarContribution): () => void {
    if (this.sidebar.has(contribution.id)) {
      throw new Error(`Duplicate sidebar contribution: ${contribution.id}`);
    }
    this.sidebar.set(contribution.id, contribution);
    this.publish();
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      this.sidebar.delete(contribution.id);
      this.publish();
    };
  }

  listSidebar(): readonly SidebarContribution[] {
    return this.snapshot;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private publish(): void {
    this.snapshot = [...this.sidebar.values()].sort(
      (a, b) => a.order - b.order || a.id.localeCompare(b.id),
    );
    this.listeners.forEach((listener) => listener());
  }
}

export const uiContributions = new UiContributionRegistry();

export function useSidebarContributions(): readonly SidebarContribution[] {
  return useSyncExternalStore(
    uiContributions.subscribe,
    () => uiContributions.listSidebar(),
    () => uiContributions.listSidebar(),
  );
}
