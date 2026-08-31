/** 文件职责：settings 前端功能：页面级界面与交互编排。 */
import { useState, useCallback, useEffect, useRef, useMemo } from "react";
import {
  X,
  Bot,
  Palette,
  Terminal,
  Cpu,
  Gauge,
  ChevronDown,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Plus,
  Download,
  Upload,
} from "lucide-react";
import { useT } from "@/app/i18n";
import { useSettingsStore } from "@/plugins/system/settings/model/settingsStore";
import { useRunsStore } from "@/plugins/automation/runs/model/runsStore";
import type {
  ProtocolType,
  ModelPreset,
} from "@/plugins/system/settings/model/settingsStore";
import { BUILTIN_BASEMAPS } from "@/shared/geo";
import { useMapStore } from "@/plugins/gis/map/model/mapStore";
import { mapEngine } from "@/plugins/gis/map/engine/MapEngine";
import { backendClient } from "@/shared/backend/backendClient";
import { iconMap, PROVIDERS, type ProviderConfig } from "./model/providerMap";
import {
  isDeepSeekRoute,
  runCreatedAtMs,
  summarizePromptCacheRuns,
  summarizePromptCacheUsage,
} from "./model/promptCacheMetrics";
import {
  SettingItem,
  SettingInput,
  SettingNumber,
  SettingSelect,
  SettingSlider,
  SettingSection,
} from "./components/SettingItem";
import { AgentSettingsSection } from "./sections/AgentSettingsSection";
import { AppearanceSettingsSection } from "./sections/AppearanceSettingsSection";
import { BackendSettingsSection } from "./sections/BackendSettingsSection";
import { PromptCacheSettingsSection } from "./sections/PromptCacheSettingsSection";
import {
  SettingsHeader,
  SettingsNavigation,
  type NavSection,
} from "./components/SettingsChrome";

// ─── Protocol options ──────────────────────────────────────────

// Protocol options are now generated inside the component to use translations

// ─── Navigation sections ───────────────────────────────────────

// ─── SettingsView ──────────────────────────────────────────────

export function SettingsView() {
  const t = useT();

  const PROTOCOL_OPTIONS: {
    value: ProtocolType;
    label: string;
    description: string;
  }[] = [
    {
      value: "openai",
      label: t.settings.openaiProtocol,
      description: t.settings.openaiProtocolDesc,
    },
    {
      value: "anthropic",
      label: t.settings.anthropicProtocol,
      description: t.settings.anthropicProtocolDesc,
    },
  ];

  const NAV_SECTIONS = useMemo<NavSection[]>(
    () => [
      {
        id: "model",
        label: t.settings.model,
        icon: Bot,
        keywords: [
          "llm",
          "api",
          "key",
          "protocol",
          "model",
          "temperature",
          "token",
        ],
      },
      {
        id: "agent",
        label: t.settings.agent,
        icon: Cpu,
        keywords: [
          "agent",
          "iteration",
          "confirmation",
          "timeout",
          "instructions",
        ],
      },
      {
        id: "promptCache",
        label: t.settings.promptCacheTest,
        icon: Gauge,
        keywords: [
          "cache",
          "prompt cache",
          "deepseek",
          "usage",
          "token",
          "section",
          "llm",
        ],
      },
      {
        id: "appearance",
        label: t.settings.appearance,
        icon: Palette,
        keywords: ["theme", "font", "language", "map", "dark", "light"],
      },
      {
        id: "backend",
        label: t.settings.backend,
        icon: Terminal,
        keywords: ["java", "backend", "runtime", "jdk", "sidecar"],
      },
    ],
    [t],
  );
  const [searchQuery, setSearchQuery] = useState("");
  const [activeSection, setActiveSection] = useState("model");
  const [saveStatus, setSaveStatus] = useState<
    "idle" | "saving" | "saved" | "error"
  >("idle");
  const [testStatus, setTestStatus] = useState<
    "idle" | "testing" | "success" | "error"
  >("idle");
  const [backendStatus, setBackendStatus] = useState<
    "stopped" | "starting" | "ready" | "error"
  >("stopped");
  const [backendError, setBackendError] = useState<string>("");
  const [backendRestarting, setBackendRestarting] = useState(false);
  const [showSaveAsNew, setShowSaveAsNew] = useState(false);
  const [newPresetName, setNewPresetName] = useState("");
  const [showProviderDropdown, setShowProviderDropdown] = useState(false);
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(
    null,
  );
  const [loadedPresetId, setLoadedPresetId] = useState<string | null>(null);
  const [promptCacheTestEnabled, setPromptCacheTestEnabled] = useState(() => {
    try {
      return (
        window.localStorage.getItem(
          "opengis.settings.promptCacheTest.enabled",
        ) === "1"
      );
    } catch {
      return false;
    }
  });
  const [promptCacheTestExpanded, setPromptCacheTestExpanded] = useState(() => {
    try {
      return (
        window.localStorage.getItem(
          "opengis.settings.promptCacheTest.expanded",
        ) === "1"
      );
    } catch {
      return false;
    }
  });
  const [promptCacheClearedAt, setPromptCacheClearedAt] = useState(() => {
    try {
      return Number(
        window.localStorage.getItem(
          "opengis.settings.promptCacheTest.clearedAt",
        ) || 0,
      );
    } catch {
      return 0;
    }
  });

  const contentRef = useRef<HTMLDivElement>(null);
  const sectionRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const providerDropdownRef = useRef<HTMLDivElement>(null);

  // Close provider dropdown on outside click
  useEffect(() => {
    if (!showProviderDropdown) return;
    const handler = (e: MouseEvent) => {
      if (
        providerDropdownRef.current &&
        !providerDropdownRef.current.contains(e.target as Node)
      ) {
        setShowProviderDropdown(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [showProviderDropdown]);

  const {
    model,
    appearance,
    agent,
    updateModel,
    updateAppearance,
    updateAgent,
    loadFromElectron,
    saveToElectron,
  } = useSettingsStore();
  const runs = useRunsStore((s) => s.runs);
  const runsLoaded = useRunsStore((s) => s.loaded);
  const refreshRuns = useRunsStore((s) => s.refresh);
  const getRunDetail = useRunsStore((s) => s.getDetail);
  const runDetails = useRunsStore((s) => s.details);

  // Load settings on mount
  useEffect(() => {
    loadFromElectron();
  }, [loadFromElectron]);

  useEffect(() => {
    if (!promptCacheTestEnabled) return;
    if (!runsLoaded) {
      refreshRuns(12).catch(() => {});
    }
  }, [promptCacheTestEnabled, runsLoaded, refreshRuns]);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        "opengis.settings.promptCacheTest.enabled",
        promptCacheTestEnabled ? "1" : "0",
      );
    } catch {}
  }, [promptCacheTestEnabled]);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        "opengis.settings.promptCacheTest.expanded",
        promptCacheTestExpanded ? "1" : "0",
      );
    } catch {}
  }, [promptCacheTestExpanded]);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        "opengis.settings.promptCacheTest.clearedAt",
        String(promptCacheClearedAt || 0),
      );
    } catch {}
  }, [promptCacheClearedAt]);

  useEffect(() => {
    if (!promptCacheTestEnabled) return;
    const timer = window.setInterval(() => {
      refreshRuns(12).catch(() => {});
    }, 10000);
    return () => window.clearInterval(timer);
  }, [promptCacheTestEnabled, refreshRuns]);

  // Initialize selectedProviderId from current baseURL
  useEffect(() => {
    if (model.baseURL && !selectedProviderId) {
      const match = PROVIDERS.find((p) => p.baseURL === model.baseURL);
      if (match) setSelectedProviderId(match.id);
    }
  }, [model.baseURL]); // eslint-disable-line react-hooks/exhaustive-deps

  // Load user instructions from backend (source of truth)
  useEffect(() => {
    let cancelled = false;
    backendClient
      .send("user_instructions.get", {})
      .then((res: any) => {
        if (!cancelled && res?.content) {
          useSettingsStore
            .getState()
            .updateAgent({ customInstructions: res.content });
        }
      })
      .catch(() => {
        /* backend may not be ready yet */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Monitor the application backend status.
  useEffect(() => {
    // Fetch initial status
    window.electronAPI
      ?.getBackendStatus()
      .then((status) => {
        if (status) {
          setBackendStatus(status.status);
          setBackendError(status.error || "");
        }
      })
      .catch(() => {});

    // Listen for status changes
    const unsubscribe = window.electronAPI?.onBackendStatusChanged((status) => {
      setBackendStatus(status.status);
      setBackendError(status.error || "");
      if (status.status === "ready" || status.status === "error") {
        setBackendRestarting(false);
      }
    });

    return unsubscribe ?? (() => {});
  }, []);

  // Restart the bundled Java backend.
  const handleRestartBackend = useCallback(async () => {
    if (!window.electronAPI) return;
    setBackendRestarting(true);
    setBackendError("");
    try {
      // Flush any pending debounced save so the restart reads the latest settings
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
        saveTimeoutRef.current = undefined;
      }
      await saveToElectron();

      const status = await window.electronAPI.restartBackend();
      if (status) {
        setBackendStatus(status.status);
        setBackendError(status.error || "");
        if (status.status === "ready" || status.status === "error") {
          setBackendRestarting(false);
        }
      }
    } catch (err: any) {
      setBackendStatus("error");
      setBackendError(err.message || String(err));
      setBackendRestarting(false);
    }
  }, [saveToElectron]);

  // Auto-save with debounce
  const saveTimeoutRef = useRef<ReturnType<typeof setTimeout>>();
  const handleSave = useCallback(() => {
    if (saveTimeoutRef.current) clearTimeout(saveTimeoutRef.current);
    setSaveStatus("saving");
    saveTimeoutRef.current = setTimeout(async () => {
      try {
        await saveToElectron();
        setSaveStatus("saved");
        setTimeout(() => setSaveStatus("idle"), 2000);
      } catch {
        setSaveStatus("error");
        setTimeout(() => setSaveStatus("idle"), 3000);
      }
    }, 500);
  }, [saveToElectron]);

  // Wrap update functions to auto-save
  const setModel = useCallback(
    (updates: Partial<typeof model>) => {
      updateModel(updates);
      handleSave();
    },
    [updateModel, handleSave],
  );

  const setAgent = useCallback(
    (updates: Partial<typeof agent>) => {
      updateAgent(updates);
      handleSave();
    },
    [updateAgent, handleSave],
  );

  const setAppearance = useCallback(
    (updates: Partial<typeof appearance>) => {
      updateAppearance(updates);
      handleSave();
    },
    [updateAppearance, handleSave],
  );

  // Preset helpers
  const presets = useMemo(() => model.presets || [], [model.presets]);

  // Resolve icon for a provider id
  const loadProviderIcon = useCallback((providerId?: string) => {
    if (!providerId) return null;
    return iconMap[providerId] || null;
  }, []);

  const handleProviderSelect = useCallback(
    (provider: ProviderConfig) => {
      // Record user's explicit selection so that manually editing baseURL later
      // won't reset the displayed provider label to "Custom".
      setSelectedProviderId(provider.id);
      const updates: Partial<typeof model> = {
        protocol: provider.protocol,
      };
      if (!model.baseURL) {
        updates.baseURL = provider.baseURL;
      }
      if (!model.modelName) {
        updates.modelName = provider.defaultModel || "";
      }
      setModel(updates);
      setShowProviderDropdown(false);
    },
    [setModel, model.baseURL, model.modelName],
  );

  const loadPreset = useCallback(
    (preset: ModelPreset) => {
      setModel({
        protocol: preset.protocol,
        modelName: preset.modelName,
        apiKey: preset.apiKey,
        baseURL: preset.baseURL,
      });
      setLoadedPresetId(preset.id);
      // Restore provider selection from preset
      setSelectedProviderId(preset.provider || null);
    },
    [setModel],
  );

  const updateActivePreset = useCallback(() => {
    if (!loadedPresetId) return;
    setModel({
      presets: presets.map((p) =>
        p.id === loadedPresetId
          ? {
              ...p,
              protocol: model.protocol,
              modelName: model.modelName,
              apiKey: model.apiKey,
              baseURL: model.baseURL,
            }
          : p,
      ),
    });
  }, [
    loadedPresetId,
    presets,
    model.protocol,
    model.modelName,
    model.apiKey,
    model.baseURL,
    setModel,
  ]);

  const createNewPreset = useCallback(() => {
    const name = newPresetName.trim();
    if (!name) return;
    const matchedProvider = PROVIDERS.find(
      (p) => p.baseURL && model.baseURL && p.baseURL === model.baseURL,
    );
    const newPreset: ModelPreset = {
      id: crypto.randomUUID(),
      name,
      provider: matchedProvider?.id || "",
      protocol: model.protocol,
      modelName: model.modelName,
      apiKey: model.apiKey,
      baseURL: model.baseURL,
    };
    setModel({ presets: [...presets, newPreset] });
    setLoadedPresetId(newPreset.id);
    setNewPresetName("");
    setShowSaveAsNew(false);
  }, [
    newPresetName,
    model.protocol,
    model.modelName,
    model.apiKey,
    model.baseURL,
    presets,
    setModel,
  ]);

  const deletePreset = useCallback(
    (id: string) => {
      setModel({ presets: presets.filter((p) => p.id !== id) });
      if (loadedPresetId === id) setLoadedPresetId(null);
    },
    [presets, loadedPresetId, setModel],
  );

  // ─── Import / Export model config as JSON ───────────────────────
  const exportModelConfig = useCallback(() => {
    const config = {
      protocol: model.protocol,
      modelName: model.modelName,
      apiKey: model.apiKey,
      baseURL: model.baseURL,
      temperature: model.temperature,
      maxTokens: model.maxTokens,
      contextWindow: model.contextWindow,
      reasoningEffort: model.reasoningEffort,
    };
    const json = JSON.stringify(config, null, 2);
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `model-config-${model.modelName || "default"}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, [model]);

  const exportPreset = useCallback((preset: ModelPreset) => {
    const config = {
      name: preset.name,
      protocol: preset.protocol,
      modelName: preset.modelName,
      apiKey: preset.apiKey,
      baseURL: preset.baseURL,
    };
    const json = JSON.stringify(config, null, 2);
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `preset-${preset.name || "config"}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  const importModelConfig = useCallback(() => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".json";
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      try {
        const text = await file.text();
        const config = JSON.parse(text);
        const updates: Partial<typeof model> = {};
        if (
          config.protocol &&
          (config.protocol === "openai" || config.protocol === "anthropic")
        ) {
          updates.protocol = config.protocol;
        }
        if (typeof config.modelName === "string")
          updates.modelName = config.modelName;
        if (typeof config.apiKey === "string") updates.apiKey = config.apiKey;
        if (typeof config.baseURL === "string")
          updates.baseURL = config.baseURL;
        if (typeof config.temperature === "number")
          updates.temperature = config.temperature;
        if (typeof config.maxTokens === "number")
          updates.maxTokens = config.maxTokens;
        if (typeof config.contextWindow === "number")
          updates.contextWindow = config.contextWindow;
        if (
          config.reasoningEffort &&
          ["low", "medium", "high"].includes(config.reasoningEffort)
        ) {
          updates.reasoningEffort = config.reasoningEffort;
        }
        setModel(updates);
      } catch (err) {
        console.error("[Settings] Failed to import model config:", err);
      }
    };
    input.click();
  }, [setModel]);

  // Scroll to section
  const scrollToSection = useCallback((sectionId: string) => {
    setActiveSection(sectionId);
    const el = sectionRefs.current[sectionId];
    if (el) {
      el.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, []);

  // Intersection observer for active section tracking
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setActiveSection(entry.target.id.replace("section-", ""));
          }
        }
      },
      {
        root: contentRef.current,
        rootMargin: "-20% 0px -70% 0px",
        threshold: 0,
      },
    );

    for (const section of NAV_SECTIONS) {
      const el = sectionRefs.current[section.id];
      if (el) observer.observe(el);
    }

    return () => observer.disconnect();
  }, [NAV_SECTIONS]);

  // Sync showMapLabels setting to map on load and when changed
  useEffect(() => {
    const checked = appearance.showMapLabels;
    const store = useMapStore.getState();
    store.setLabelsVisible(checked);

    const currentBasemap = store.basemap;
    // Raster basemaps: switch to the appropriate variant
    if (currentBasemap.type === "raster-tiles") {
      const targetId = checked ? "carto-voyager" : "carto-voyager-nolabels";
      const target = BUILTIN_BASEMAPS.find((b) => b.id === targetId);
      if (target && target.id !== currentBasemap.id) {
        store.setBasemap(target);
      }
      return;
    }
    // Vector basemaps: try -nolabels variant
    const currentId = currentBasemap.id;
    if (!checked) {
      const noLabelsId = currentId + "-nolabels";
      const noLabelsBasemap = BUILTIN_BASEMAPS.find((b) => b.id === noLabelsId);
      if (noLabelsBasemap) {
        store.setBasemap(noLabelsBasemap);
        return;
      }
    } else if (currentId.endsWith("-nolabels")) {
      const withLabelsId = currentId.replace("-nolabels", "");
      const withLabelsBasemap = BUILTIN_BASEMAPS.find(
        (b) => b.id === withLabelsId,
      );
      if (withLabelsBasemap) {
        store.setBasemap(withLabelsBasemap);
        return;
      }
    }
    // Fallback: toggle symbol layers directly via MapEngine
    const applyToMap = () => {
      const map = mapEngine.getMap();
      if (map && map.isStyleLoaded()) {
        mapEngine.setLabelsVisible(checked);
      }
    };
    applyToMap();
    const map = mapEngine.getMap();
    if (map && !map.isStyleLoaded()) {
      map.once("style.load", () => {
        mapEngine.setLabelsVisible(checked);
      });
    }
  }, [appearance.showMapLabels]);

  // Filter sections by search
  const filteredSections = useMemo(() => {
    if (!searchQuery.trim()) return NAV_SECTIONS;
    const q = searchQuery.toLowerCase();
    return NAV_SECTIONS.filter(
      (s) =>
        s.label.toLowerCase().includes(q) ||
        s.keywords.some((k) => k.includes(q)),
    );
  }, [searchQuery, NAV_SECTIONS]);

  const visiblePromptCacheRuns = useMemo(
    () =>
      runs.filter(
        (run) => runCreatedAtMs(run.created_at) > promptCacheClearedAt,
      ),
    [runs, promptCacheClearedAt],
  );
  const latestRun = visiblePromptCacheRuns[0] || null;
  const latestRunDetail = latestRun
    ? runDetails[latestRun.run_id] || null
    : null;
  const latestRunUsage =
    latestRunDetail?.llm_usage?.[latestRunDetail.llm_usage.length - 1] || null;
  const latestRunUsages = useMemo(
    () => latestRunDetail?.llm_usage || [],
    [latestRunDetail],
  );
  const promptCacheLoopPoints = useMemo(
    () =>
      summarizePromptCacheRuns(visiblePromptCacheRuns.slice(0, 12), runDetails),
    [visiblePromptCacheRuns, runDetails],
  );
  const currentRouteIsDeepSeek = useMemo(
    () => isDeepSeekRoute(model.modelName, model.baseURL),
    [model.modelName, model.baseURL],
  );
  const promptCacheStats = useMemo(
    () => summarizePromptCacheUsage(latestRunUsages),
    [latestRunUsages],
  );

  useEffect(() => {
    if (!promptCacheTestEnabled) return;
    if (!latestRun) return;
    const shouldForce =
      !latestRunDetail ||
      latestRunDetail.status !== latestRun.status ||
      !Array.isArray(latestRunDetail.llm_usage);
    getRunDetail(latestRun.run_id, shouldForce).catch(() => {});
  }, [promptCacheTestEnabled, latestRun, latestRunDetail, getRunDetail]);

  useEffect(() => {
    if (!promptCacheTestEnabled || !promptCacheTestExpanded) return;
    for (const run of visiblePromptCacheRuns.slice(0, 12)) {
      const detail = runDetails[run.run_id];
      getRunDetail(
        run.run_id,
        !detail || !Array.isArray(detail.llm_usage),
      ).catch(() => {});
    }
  }, [
    promptCacheTestEnabled,
    promptCacheTestExpanded,
    visiblePromptCacheRuns,
    runDetails,
    getRunDetail,
  ]);

  // Test provider connectivity through the Java backend.
  const handleTestConnection = useCallback(async () => {
    setTestStatus("testing");
    try {
      if (!model.apiKey) {
        setTestStatus("error");
        setTimeout(() => setTestStatus("idle"), 3000);
        return;
      }

      const result = await backendClient.send("rpc.agent.test_connection", {
        protocol: model.protocol,
        model: model.modelName || "gpt-4o",
        api_key: model.apiKey,
        base_url: model.baseURL || undefined,
      });

      if (result.ok) {
        setTestStatus("success");
      } else {
        console.error("[Settings] API test failed:", result.error);
        setTestStatus("error");
      }
      setTimeout(() => setTestStatus("idle"), 3000);
    } catch (err) {
      console.error("[Settings] API test error:", err);
      setTestStatus("error");
      setTimeout(() => setTestStatus("idle"), 3000);
    }
  }, [model.apiKey, model.baseURL, model.protocol, model.modelName]);

  return (
    <div className="w-full h-full flex flex-col bg-bg-primary overflow-hidden">
      <SettingsHeader
        saveStatus={saveStatus}
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
      />
      {/* === Body: Nav + Content === */}
      <div className="flex-1 flex overflow-hidden">
        <SettingsNavigation
          sections={filteredSections}
          activeSection={activeSection}
          onSelect={scrollToSection}
        />
        {/* Right content — scrollable settings list */}
        <div
          ref={contentRef}
          className="flex-1 overflow-y-auto px-6 py-4"
          style={{
            scrollbarWidth: "thin",
            scrollbarColor: "var(--text-muted) transparent",
          }}
        >
          <div className="max-w-[700px]">
            {/* 模型配置 */}
            {filteredSections.some((s) => s.id === "model") && (
              <div
                id="section-model"
                ref={(el) => {
                  sectionRefs.current["model"] = el;
                }}
              >
                <SettingSection title={t.settings.modelConfig}>
                  {/* ── Preset cards grid ── */}
                  {presets.length > 0 && (
                    <div className="py-3 px-1 border-b border-border">
                      <div className="flex flex-wrap gap-2">
                        {presets.map((p) => {
                          const icon = loadProviderIcon(p.provider);
                          const isActive = loadedPresetId === p.id;
                          return (
                            <button
                              key={p.id}
                              onClick={() => loadPreset(p)}
                              className={`
                                group relative w-[90px] h-[68px] rounded-lg
                                flex flex-col items-center justify-center gap-1
                                transition-all duration-150
                                ${
                                  isActive
                                    ? "bg-accent-primary/15 ring-1.5 ring-accent-primary/40 text-accent-primary"
                                    : "bg-bg-secondary hover:bg-bg-hover text-text-secondary hover:text-text-primary"
                                }
                              `}
                            >
                              {icon ? (
                                <div
                                  className="w-6 h-6 flex items-center justify-center [&>svg]:w-full [&>svg]:h-full"
                                  dangerouslySetInnerHTML={{ __html: icon }}
                                />
                              ) : (
                                <div className="w-6 h-6 rounded-full bg-bg-hover flex items-center justify-center text-[10px] font-bold uppercase">
                                  {p.name.charAt(0)}
                                </div>
                              )}
                              <span className="text-[10px] font-medium truncate max-w-[80px] leading-none">
                                {p.name}
                              </span>
                              {/* Export on hover */}
                              <span
                                role="button"
                                tabIndex={0}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  exportPreset(p);
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === "Enter") {
                                    e.stopPropagation();
                                    exportPreset(p);
                                  }
                                }}
                                className="hidden group-hover:flex absolute -top-1.5 -left-1.5 items-center justify-center w-4 h-4 rounded-full bg-bg-tertiary border border-border text-text-muted hover:text-accent-primary hover:border-accent-primary/50"
                                title={t.settings.exportConfig}
                              >
                                <Download className="w-2.5 h-2.5" />
                              </span>
                              {/* Delete on hover */}
                              <span
                                role="button"
                                tabIndex={0}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  deletePreset(p.id);
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === "Enter") {
                                    e.stopPropagation();
                                    deletePreset(p.id);
                                  }
                                }}
                                className="hidden group-hover:flex absolute -top-1.5 -right-1.5 items-center justify-center w-4 h-4 rounded-full bg-bg-tertiary border border-border text-text-muted hover:text-accent-danger hover:border-accent-danger/50"
                              >
                                <X className="w-2.5 h-2.5" />
                              </span>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* ── Provider selector ── */}
                  <div className="py-3 px-1 border-b border-border">
                    <label className="text-xs font-medium text-text-muted mb-1.5 block">
                      {t.settings.provider}
                    </label>
                    <div ref={providerDropdownRef} className="relative">
                      <button
                        onClick={() =>
                          setShowProviderDropdown(!showProviderDropdown)
                        }
                        className="h-9 w-full max-w-[400px] px-3 text-sm rounded border border-border bg-bg-tertiary text-text-primary hover:border-accent-primary/50 transition-colors flex items-center justify-between"
                      >
                        <div className="flex items-center gap-2">
                          {(() => {
                            const match =
                              PROVIDERS.find(
                                (p) => p.id === selectedProviderId,
                              ) ||
                              PROVIDERS.find(
                                (p) =>
                                  p.baseURL &&
                                  model.baseURL &&
                                  p.baseURL === model.baseURL,
                              );
                            if (match && iconMap[match.id]) {
                              return (
                                <div
                                  className="w-5 h-5 flex items-center justify-center shrink-0 [&>svg]:w-full [&>svg]:h-full"
                                  dangerouslySetInnerHTML={{
                                    __html: iconMap[match.id],
                                  }}
                                />
                              );
                            }
                            return null;
                          })()}
                          <span>
                            {(() => {
                              const match =
                                PROVIDERS.find(
                                  (p) => p.id === selectedProviderId,
                                ) ||
                                PROVIDERS.find(
                                  (p) =>
                                    p.baseURL &&
                                    model.baseURL &&
                                    p.baseURL === model.baseURL,
                                );
                              return match
                                ? match.label
                                : model.baseURL
                                  ? t.settings.custom
                                  : t.settings.selectProvider;
                            })()}
                          </span>
                        </div>
                        <ChevronDown className="w-4 h-4 text-text-muted shrink-0" />
                      </button>

                      {showProviderDropdown && (
                        <div className="absolute z-50 top-full left-0 mt-1 w-[360px] max-h-[320px] overflow-y-auto rounded-lg border border-border bg-bg-secondary shadow-xl p-2">
                          <div className="grid grid-cols-4 gap-1">
                            {PROVIDERS.map((provider) => {
                              const icon = iconMap[provider.id];
                              const isActive =
                                provider.id === selectedProviderId ||
                                (!selectedProviderId &&
                                  model.baseURL === provider.baseURL);
                              return (
                                <button
                                  key={provider.id}
                                  onClick={() => handleProviderSelect(provider)}
                                  className={`
                                    flex flex-col items-center gap-1.5 p-2 rounded-md text-center transition-colors
                                    ${
                                      isActive
                                        ? "bg-accent-primary/15 text-accent-primary"
                                        : "text-text-secondary hover:bg-bg-hover hover:text-text-primary"
                                    }
                                  `}
                                  title={`${provider.label} · ${provider.protocol} · ${provider.defaultModel}`}
                                >
                                  {icon ? (
                                    <div
                                      className="w-7 h-7 flex items-center justify-center [&>svg]:w-full [&>svg]:h-full"
                                      dangerouslySetInnerHTML={{ __html: icon }}
                                    />
                                  ) : (
                                    <div className="w-7 h-7 rounded-full bg-bg-hover flex items-center justify-center text-xs font-bold uppercase">
                                      {provider.label.charAt(0)}
                                    </div>
                                  )}
                                  <span className="text-[10px] leading-tight truncate w-full">
                                    {provider.label}
                                  </span>
                                </button>
                              );
                            })}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Protocol Type */}
                  <SettingItem
                    id="model-protocol"
                    label={t.settings.protocol}
                    description={t.settings.protocolDesc}
                  >
                    <SettingSelect
                      id="model-protocol"
                      value={model.protocol}
                      onChange={(v) => {
                        const newProtocol = v as ProtocolType;
                        if (newProtocol === model.protocol) return;
                        // Only change protocol — leave all other fields intact
                        setModel({ protocol: newProtocol });
                      }}
                      options={PROTOCOL_OPTIONS.map((p) => ({
                        value: p.value,
                        label: p.label,
                      }))}
                      className="min-w-[260px]"
                    />
                  </SettingItem>

                  {/* API Key */}
                  <SettingItem
                    id="model-apikey"
                    label={t.settings.apiKey}
                    description={t.settings.apiKeyDesc}
                  >
                    <SettingInput
                      id="model-apikey"
                      type="password"
                      value={model.apiKey}
                      onChange={(v) => setModel({ apiKey: v })}
                      placeholder="sk-..."
                    />
                  </SettingItem>

                  {/* Base URL */}
                  <SettingItem
                    id="model-baseurl"
                    label={t.settings.baseURL}
                    description={t.settings.baseURLDesc}
                  >
                    <SettingInput
                      id="model-baseurl"
                      value={model.baseURL}
                      onChange={(v) => setModel({ baseURL: v })}
                      placeholder={
                        model.protocol === "openai"
                          ? "https://api.openai.com/v1"
                          : "https://api.anthropic.com/v1"
                      }
                    />
                  </SettingItem>

                  {/* Model Name */}
                  <SettingItem
                    id="model-name"
                    label={t.settings.modelName}
                    description={t.settings.modelNameDesc}
                  >
                    <SettingInput
                      id="model-name"
                      value={model.modelName}
                      onChange={(v) => setModel({ modelName: v })}
                      placeholder={
                        model.protocol === "openai"
                          ? "e.g., gpt-4o, deepseek-v4-flash"
                          : "e.g., claude-3-5-sonnet, MiniMax-M2.7"
                      }
                    />
                  </SettingItem>

                  {/* Test Connection */}
                  <SettingItem
                    id="model-test"
                    label={t.settings.testConnection}
                    description={t.settings.testConnectionDesc}
                  >
                    <button
                      onClick={handleTestConnection}
                      disabled={testStatus === "testing"}
                      className="
                        h-[30px] px-4 text-sm font-medium rounded
                        bg-accent-primary/15 text-accent-primary
                        hover:bg-accent-primary/25
                        disabled:opacity-50 disabled:cursor-not-allowed
                        transition-colors
                        flex items-center gap-2
                      "
                    >
                      {testStatus === "testing" && (
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      )}
                      {testStatus === "success" && (
                        <CheckCircle2 className="w-3.5 h-3.5 text-accent-success" />
                      )}
                      {testStatus === "error" && (
                        <AlertCircle className="w-3.5 h-3.5 text-accent-danger" />
                      )}
                      {testStatus === "idle" && t.settings.testConnection}
                      {testStatus === "testing" && t.settings.testing}
                      {testStatus === "success" && t.settings.connected}
                      {testStatus === "error" && t.common.failed}
                    </button>
                  </SettingItem>

                  {/* ── Save actions ── */}
                  <div className="py-3 px-1 flex items-center gap-2">
                    {loadedPresetId && (
                      <button
                        onClick={updateActivePreset}
                        className="h-8 px-3.5 text-xs font-medium rounded-md bg-accent-primary text-white hover:bg-accent-primary/90 transition-colors flex items-center gap-1.5"
                      >
                        <CheckCircle2 className="w-3.5 h-3.5" />
                        {t.settings.updatePreset}
                      </button>
                    )}
                    {showSaveAsNew ? (
                      <div className="flex items-center gap-1.5">
                        <input
                          autoFocus
                          value={newPresetName}
                          onChange={(e) => setNewPresetName(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") createNewPreset();
                            if (e.key === "Escape") {
                              setShowSaveAsNew(false);
                              setNewPresetName("");
                            }
                          }}
                          placeholder={t.settings.presetName}
                          className="h-8 px-2.5 text-xs rounded-md border border-border bg-bg-tertiary text-text-primary placeholder:text-text-muted focus:outline-none focus:ring-1 focus:ring-accent-primary w-36"
                        />
                        <button
                          onClick={createNewPreset}
                          className="h-8 w-8 flex items-center justify-center rounded-md bg-accent-primary/15 text-accent-primary hover:bg-accent-primary/25"
                        >
                          <CheckCircle2 className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={() => {
                            setShowSaveAsNew(false);
                            setNewPresetName("");
                          }}
                          className="h-8 w-8 flex items-center justify-center rounded-md hover:bg-bg-hover text-text-muted"
                        >
                          <X className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    ) : (
                      <button
                        onClick={() => setShowSaveAsNew(true)}
                        className="h-8 px-3.5 text-xs font-medium rounded-md border border-border text-text-secondary hover:text-text-primary hover:border-accent-primary/50 hover:bg-accent-primary/5 transition-colors flex items-center gap-1.5"
                      >
                        <Plus className="w-3.5 h-3.5" />
                        {t.settings.saveAsNew}
                      </button>
                    )}
                    {/* Import / Export */}
                    <div className="ml-auto flex items-center gap-1.5">
                      <button
                        onClick={importModelConfig}
                        className="h-8 px-3 text-xs font-medium rounded-md border border-border text-text-secondary hover:text-text-primary hover:border-accent-primary/50 hover:bg-accent-primary/5 transition-colors flex items-center gap-1.5"
                        title={t.settings.importConfig}
                      >
                        <Upload className="w-3.5 h-3.5" />
                        {t.settings.importConfig}
                      </button>
                      <button
                        onClick={exportModelConfig}
                        className="h-8 px-3 text-xs font-medium rounded-md border border-border text-text-secondary hover:text-text-primary hover:border-accent-primary/50 hover:bg-accent-primary/5 transition-colors flex items-center gap-1.5"
                        title={t.settings.exportConfig}
                      >
                        <Download className="w-3.5 h-3.5" />
                        {t.settings.exportConfig}
                      </button>
                    </div>
                  </div>
                </SettingSection>

                <SettingSection title={t.settings.modelParams}>
                  {/* Temperature */}
                  <SettingItem
                    id="model-temperature"
                    label={t.settings.temperature}
                    description={t.settings.temperatureDesc}
                  >
                    <SettingSlider
                      id="model-temperature"
                      value={model.temperature}
                      onChange={(v) => setModel({ temperature: v })}
                      min={0}
                      max={1}
                      step={0.05}
                    />
                  </SettingItem>

                  {/* Max Tokens */}
                  <SettingItem
                    id="model-maxtokens"
                    label={t.settings.maxTokens}
                    description={t.settings.maxTokensDesc}
                  >
                    <SettingNumber
                      id="model-maxtokens"
                      value={model.maxTokens}
                      onChange={(v) => setModel({ maxTokens: v })}
                      min={256}
                      max={200000}
                      step={256}
                    />
                  </SettingItem>

                  {/* Context Window */}
                  <SettingItem
                    id="model-context-window"
                    label={t.settings.contextWindow}
                    description={t.settings.contextWindowDesc}
                  >
                    <SettingNumber
                      id="model-context-window"
                      value={model.contextWindow}
                      onChange={(v) => setModel({ contextWindow: v })}
                      min={4096}
                      max={2000000}
                      step={1024}
                    />
                  </SettingItem>

                  {/* Reasoning Effort */}
                  <SettingItem
                    id="model-reasoning"
                    label={t.settings.reasoningEffort}
                    description={t.settings.reasoningEffortDesc}
                  >
                    <SettingSelect
                      id="model-reasoning"
                      value={model.reasoningEffort}
                      onChange={(v) =>
                        setModel({
                          reasoningEffort: v as "low" | "medium" | "high",
                        })
                      }
                      options={[
                        { value: "low", label: t.settings.reasoningLow },
                        { value: "medium", label: t.settings.reasoningMedium },
                        { value: "high", label: t.settings.reasoningHigh },
                      ]}
                    />
                  </SettingItem>
                </SettingSection>
              </div>
            )}

            {/* Agent 配置 */}
            {filteredSections.some((s) => s.id === "agent") && (
              <AgentSettingsSection
                value={agent}
                onChange={setAgent}
                sectionRef={(element) => {
                  sectionRefs.current.agent = element;
                }}
              />
            )}
            {/* DeepSeek Prompt Cache 测试 */}
            {filteredSections.some((s) => s.id === "promptCache") && (
              <PromptCacheSettingsSection
                model={model}
                promptCacheTestEnabled={promptCacheTestEnabled}
                onEnabledChange={setPromptCacheTestEnabled}
                promptCacheTestExpanded={promptCacheTestExpanded}
                onToggleExpanded={() =>
                  setPromptCacheTestExpanded((value) => !value)
                }
                onClearHistory={() => setPromptCacheClearedAt(Date.now())}
                visiblePromptCacheRunCount={visiblePromptCacheRuns.length}
                currentRouteIsDeepSeek={currentRouteIsDeepSeek}
                promptCacheStats={promptCacheStats}
                latestRun={latestRun}
                latestRunUsage={latestRunUsage}
                promptCacheLoopPoints={promptCacheLoopPoints}
                sectionRef={(element) => {
                  sectionRefs.current.promptCache = element;
                }}
              />
            )}
            {/* 外观设置 */}
            {filteredSections.some((s) => s.id === "appearance") && (
              <AppearanceSettingsSection
                value={appearance}
                onChange={setAppearance}
                sectionRef={(element) => {
                  sectionRefs.current.appearance = element;
                }}
              />
            )}
            {/* Java backend runtime */}
            {filteredSections.some((s) => s.id === "backend") && (
              <BackendSettingsSection
                status={backendStatus}
                error={backendError}
                restarting={backendRestarting}
                onRestart={handleRestartBackend}
                sectionRef={(element) => {
                  sectionRefs.current.backend = element;
                }}
              />
            )}
            {/* Bottom spacer */}
            <div className="h-20" />
          </div>
        </div>
      </div>
    </div>
  );
}
SettingsView.displayName = "SettingsView";
