/** 文件职责：assets 前端功能：实现该文件名所对应的单一职责。 */
/**
 * AssetExplorer — file tree sidebar panel for managing project data assets.
 *
 * Features:
 * - Workspace folder selection
 * - Lazy-loaded file tree with expand/collapse
 * - File type icons with GIS format awareness
 * - Search / filter by filename
 * - Right-click context menu (add to map, rename, delete, properties)
 * - Layer association indicators
 * - Drag & drop files to add as layers
 * - Sort by name / type / modified / size
 */
import { useState, useCallback, useEffect, useRef } from "react";
import {
  FolderOpen,
  Search,
  RefreshCw,
  ChevronRight,
  X,
  ArrowUpDown,
  FolderPlus,
  ScrollText,
} from "lucide-react";
import { useT } from "@/app/i18n";
import {
  useAssetStore,
  type FileNode,
  type SortMode,
} from "@/plugins/workspace/assets/model/assetStore";
import { FileTreeNode } from "./components/AssetTree";

// ─── Main Component ───────────────────────────────────────────────

export function AssetExplorer() {
  const t = useT();
  const workspacePath = useAssetStore((s) => s.workspacePath);
  const rootNodes = useAssetStore((s) => s.rootNodes);
  const isLoading = useAssetStore((s) => s.isLoading);
  const error = useAssetStore((s) => s.error);
  const searchQuery = useAssetStore((s) => s.searchQuery);
  const setSearchQuery = useAssetStore((s) => s.setSearchQuery);
  const setWorkspacePath = useAssetStore((s) => s.setWorkspacePath);
  const setRootNodes = useAssetStore((s) => s.setRootNodes);
  const setLoading = useAssetStore((s) => s.setLoading);
  const setError = useAssetStore((s) => s.setError);
  const getFilteredNodes = useAssetStore((s) => s.getFilteredNodes);
  const collapseAll = useAssetStore((s) => s.collapseAll);
  const sortMode = useAssetStore((s) => s.sortMode);
  const setSortMode = useAssetStore((s) => s.setSortMode);

  const [showSearch, setShowSearch] = useState(false);
  const [showSortMenu, setShowSortMenu] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // ─── Load workspace directory ─────────────────────────────────

  const loadDirectory = useCallback(
    async (dirPath: string) => {
      if (!window.electronAPI?.readDirectory) return;

      setLoading(true);
      setError(null);

      try {
        const result = await window.electronAPI.readDirectory(dirPath);
        if (result.success && result.entries) {
          const nodes: FileNode[] = result.entries.map((entry: any) => ({
            path: entry.path,
            name: entry.name,
            type: entry.isDirectory ? "directory" : "file",
            extension: entry.extension || "",
            size: entry.size,
            modifiedTime: entry.modifiedTime,
            children: entry.isDirectory ? [] : undefined,
            childrenLoaded: false,
            depth: 0,
          }));
          setRootNodes(nodes);
        } else {
          setError(result.error || "Failed to read directory");
        }
      } catch (err) {
        setError(String(err));
      } finally {
        setLoading(false);
      }
    },
    [setRootNodes, setLoading, setError],
  );

  // Auto-load when workspace path changes
  useEffect(() => {
    if (workspacePath) {
      loadDirectory(workspacePath);
    }
  }, [workspacePath, loadDirectory]);

  // ─── Open folder ──────────────────────────────────────────────

  const [openingFolder, setOpeningFolder] = useState(false);

  const handleOpenFolder = useCallback(async () => {
    if (!window.electronAPI?.openFolderDialog) {
      console.warn(
        "openFolderDialog not available — running outside Electron?",
      );
      return;
    }

    setOpeningFolder(true);
    try {
      const folderPath = await window.electronAPI.openFolderDialog();
      if (folderPath) {
        setWorkspacePath(folderPath);
      }
    } finally {
      setOpeningFolder(false);
    }
  }, [setWorkspacePath]);

  // ─── Refresh ──────────────────────────────────────────────────

  const handleRefresh = useCallback(() => {
    if (workspacePath) {
      loadDirectory(workspacePath);
    }
  }, [workspacePath, loadDirectory]);

  useEffect(() => {
    const onAssetsRefresh = () => {
      if (workspacePath) {
        loadDirectory(workspacePath);
      }
    };

    window.addEventListener("opengis:assets-refresh", onAssetsRefresh);
    return () =>
      window.removeEventListener("opengis:assets-refresh", onAssetsRefresh);
  }, [workspacePath, loadDirectory]);

  // ─── Toggle search ────────────────────────────────────────────

  const handleToggleSearch = useCallback(() => {
    setShowSearch((prev) => {
      if (!prev) {
        setTimeout(() => searchInputRef.current?.focus(), 50);
      } else {
        setSearchQuery("");
      }
      return !prev;
    });
  }, [setSearchQuery]);

  // ─── Sort menu ────────────────────────────────────────────────

  const handleSortChange = useCallback(
    (mode: SortMode) => {
      setSortMode(mode);
      setShowSortMenu(false);
    },
    [setSortMode],
  );

  // ─── Open log directory ───────────────────────────────────────

  const handleOpenLogs = useCallback(async () => {
    const api = (window as any).electronAPI;
    if (!api?.openLogDir) {
      console.warn("openLogDir not available — running outside Electron?");
      return;
    }
    const res = await api.openLogDir();
    if (!res?.success) {
      console.error("Failed to open log directory:", res?.error);
    }
  }, []);

  // ─── Render ───────────────────────────────────────────────────

  const filteredNodes = getFilteredNodes();
  const workspaceName = workspacePath
    ? workspacePath.split(/[\\/]/).pop() || workspacePath
    : null;

  return (
    <div className="w-full h-full flex flex-col bg-bg-primary overflow-hidden select-none">
      {/* Header */}
      <div className="h-9 border-b border-border flex items-center px-3 shrink-0 gap-1">
        <span className="text-xs font-semibold text-text-secondary flex-1 truncate">
          {workspaceName || t.assets.explorer}
        </span>

        {/* Search toggle */}
        <button
          onClick={handleToggleSearch}
          className={`w-6 h-6 rounded flex items-center justify-center transition-colors ${
            showSearch
              ? "text-accent-primary bg-accent-primary/10"
              : "text-text-muted hover:text-accent-primary hover:bg-accent-primary/10"
          }`}
          title={t.assets.searchFiles}
        >
          <Search className="w-3.5 h-3.5" />
        </button>

        {/* Sort */}
        <div className="relative">
          <button
            onClick={() => setShowSortMenu(!showSortMenu)}
            className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
            title={t.assets.sortFiles}
          >
            <ArrowUpDown className="w-3.5 h-3.5" />
          </button>
          {showSortMenu && (
            <SortMenu
              currentMode={sortMode}
              onSelect={handleSortChange}
              onClose={() => setShowSortMenu(false)}
            />
          )}
        </div>

        {/* Refresh */}
        {workspacePath && (
          <button
            onClick={handleRefresh}
            className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
            title={t.assets.refresh}
          >
            <RefreshCw
              className={`w-3.5 h-3.5 ${isLoading ? "animate-spin" : ""}`}
            />
          </button>
        )}

        {/* Collapse all */}
        {workspacePath && (
          <button
            onClick={collapseAll}
            className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
            title={t.assets.collapseAll}
          >
            <ChevronRight className="w-3.5 h-3.5" />
          </button>
        )}

        {/* Open log directory */}
        <button
          onClick={handleOpenLogs}
          className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
          title={t.assets.revealLogs}
        >
          <ScrollText className="w-3.5 h-3.5" />
        </button>

        {/* Open folder */}
        <button
          onClick={handleOpenFolder}
          className="w-6 h-6 rounded flex items-center justify-center text-text-muted hover:text-accent-primary hover:bg-accent-primary/10 transition-colors"
          title={t.assets.openFolder}
        >
          <FolderPlus className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Search bar */}
      {showSearch && (
        <div className="px-2 py-1.5 border-b border-border shrink-0 animate-slide-up">
          <div className="flex items-center gap-1.5 bg-bg-tertiary rounded-md px-2 py-1">
            <Search className="w-3 h-3 text-text-muted shrink-0" />
            <input
              ref={searchInputRef}
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t.assets.filterPlaceholder}
              className="flex-1 bg-transparent text-xs text-text-primary placeholder:text-text-muted/50 outline-none"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery("")}
                className="text-text-muted hover:text-text-secondary"
              >
                <X className="w-3 h-3" />
              </button>
            )}
          </div>
        </div>
      )}

      {/* File tree */}
      <div className="flex-1 overflow-y-auto scrollbar-thin">
        {openingFolder ? (
          <LoadingState />
        ) : !workspacePath ? (
          <EmptyState onOpenFolder={handleOpenFolder} />
        ) : isLoading && rootNodes.length === 0 ? (
          <LoadingState />
        ) : error ? (
          <ErrorState error={error} onRetry={handleRefresh} />
        ) : filteredNodes.length === 0 ? (
          searchQuery ? (
            <NoResultsState query={searchQuery} />
          ) : (
            <EmptyFolderState />
          )
        ) : (
          <div className="py-1">
            {filteredNodes.map((node) => (
              <FileTreeNode key={node.path} node={node} depth={0} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Sort Menu ──────────────────────────────────────────────────

function SortMenu({
  currentMode,
  onSelect,
  onClose,
}: {
  currentMode: SortMode;
  onSelect: (mode: SortMode) => void;
  onClose: () => void;
}) {
  const t = useT();
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [onClose]);

  const options: { mode: SortMode; label: string }[] = [
    { mode: "name", label: t.assets.sortName },
    { mode: "type", label: t.assets.sortType },
    { mode: "modified", label: t.assets.sortDate },
    { mode: "size", label: t.assets.sortSize },
  ];

  return (
    <div
      ref={menuRef}
      className="absolute right-0 top-full mt-1 bg-bg-secondary border border-border rounded-lg shadow-xl py-1 min-w-[140px] z-50 animate-fade-in"
    >
      <div className="px-3 py-1 text-2xs text-text-muted font-medium uppercase tracking-wider">
        {t.assets.sortBy}
      </div>
      {options.map(({ mode, label }) => (
        <button
          key={mode}
          onClick={() => onSelect(mode)}
          className={`
            w-full flex items-center gap-2 px-3 py-1.5 text-xs transition-colors
            ${
              currentMode === mode
                ? "text-accent-primary bg-accent-primary/10"
                : "text-text-secondary hover:text-text-primary hover:bg-bg-hover"
            }
          `}
        >
          <span className="w-3 text-center">
            {currentMode === mode ? "✓" : ""}
          </span>
          <span>{label}</span>
        </button>
      ))}
    </div>
  );
}

// ─── State Components ───────────────────────────────────────────

function EmptyState({ onOpenFolder }: { onOpenFolder: () => void }) {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-4 h-full">
      <div className="text-center">
        <div className="w-10 h-10 rounded-xl bg-accent-primary/10 flex items-center justify-center mx-auto mb-3">
          <FolderOpen className="w-5 h-5 text-accent-primary/50" />
        </div>
        <p className="text-xs text-text-muted mb-2">{t.assets.noWorkspace}</p>
        <button
          onClick={onOpenFolder}
          className="text-2xs text-accent-primary hover:text-accent-primary/80 transition-colors flex items-center gap-1 mx-auto"
        >
          <FolderPlus className="w-3 h-3" />
          {t.assets.openWorkspace}
        </button>
      </div>
    </div>
  );
}

function LoadingState() {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-4 h-full">
      <div className="text-center">
        <RefreshCw className="w-5 h-5 text-text-muted animate-spin mx-auto mb-2" />
        <p className="text-xs text-text-muted">{t.assets.loadingFiles}</p>
      </div>
    </div>
  );
}

function ErrorState({
  error,
  onRetry,
}: {
  error: string;
  onRetry: () => void;
}) {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-4 h-full">
      <div className="text-center">
        <div className="w-10 h-10 rounded-xl bg-accent-danger/10 flex items-center justify-center mx-auto mb-3">
          <X className="w-5 h-5 text-accent-danger/50" />
        </div>
        <p className="text-xs text-text-muted mb-1">{t.assets.failedToLoad}</p>
        <p className="text-2xs text-text-muted/60 mb-2 max-w-[180px] truncate">
          {error}
        </p>
        <button
          onClick={onRetry}
          className="text-2xs text-accent-primary hover:text-accent-primary/80 transition-colors"
        >
          {t.common.retry}
        </button>
      </div>
    </div>
  );
}

function NoResultsState({ query }: { query: string }) {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-4 h-full">
      <div className="text-center">
        <Search className="w-5 h-5 text-text-muted/30 mx-auto mb-2" />
        <p className="text-xs text-text-muted">{t.assets.noFilesMatching}</p>
        <p className="text-2xs text-accent-primary truncate max-w-[160px]">
          "{query}"
        </p>
      </div>
    </div>
  );
}

function EmptyFolderState() {
  const t = useT();
  return (
    <div className="flex-1 flex items-center justify-center p-4 h-full">
      <div className="text-center">
        <FolderOpen className="w-5 h-5 text-text-muted/30 mx-auto mb-2" />
        <p className="text-xs text-text-muted">{t.assets.emptyFolder}</p>
      </div>
    </div>
  );
}
