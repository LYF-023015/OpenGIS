/** 文件职责：共享基础能力：实现该文件名所对应的单一职责。 */
/**
 * Type declarations for packages without built-in TypeScript types.
 */

// shapefile — https://github.com/mbostock/shapefile
declare module "shapefile" {
  export function read(
    shp: ArrayBuffer | string,
    dbf?: ArrayBuffer | string,
    options?: Record<string, any>,
  ): Promise<GeoJSON.FeatureCollection>;

  export function open(
    shp: ArrayBuffer | string,
    dbf?: ArrayBuffer | string,
    options?: Record<string, any>,
  ): Promise<{
    read(): Promise<{ done: boolean; value: GeoJSON.Feature }>;
    bbox: [number, number, number, number];
  }>;
}

// @tmcw/togeojson — https://github.com/tmcw/togeojson
declare module "@tmcw/togeojson" {
  export function kml(doc: Document): GeoJSON.FeatureCollection;
  export function gpx(doc: Document): GeoJSON.FeatureCollection;
}

// Electron API bridge — must match electron/preload.ts exactly
interface Window {
  electronAPI?: {
    // File system
    openFileDialog: (filters?: any[]) => Promise<string[] | null>;
    saveFileDialog: (defaultPath?: string) => Promise<string | null>;
    readFile: (
      path: string,
    ) => Promise<{ success: boolean; content?: string; error?: string }>;
    readFileAsBuffer: (
      path: string,
    ) => Promise<{ success: boolean; buffer?: ArrayBuffer; error?: string }>;
    writeFile: (
      path: string,
      content: string,
    ) => Promise<{ success: boolean; error?: string }>;
    writeFileBinary: (
      path: string,
      buffer: ArrayBuffer,
    ) => Promise<{ success: boolean; error?: string }>;
    getFileInfo: (
      path: string,
    ) => Promise<{ success: boolean; info?: any; error?: string }>;
    readDirectory: (
      path: string,
    ) => Promise<{ success: boolean; entries?: any[]; error?: string }>;
    openFolderDialog: () => Promise<string | null>;
    deleteFile: (path: string) => Promise<{ success: boolean; error?: string }>;
    renameFile: (
      oldPath: string,
      newPath: string,
    ) => Promise<{ success: boolean; error?: string }>;
    showItemInFolder: (
      path: string,
    ) => Promise<{ success: boolean; error?: string }>;
    ensureDirectory: (
      path: string,
    ) => Promise<{ success: boolean; error?: string }>;
    // Application backend (Java in production)
    getBackendStatus: () => Promise<{
      status: "stopped" | "starting" | "ready" | "error";
      runtime: "java" | "python";
      port?: number;
      error?: string;
      executablePath?: string;
      serverPath?: string;
      diagnostics?: string[];
    }>;
    restartBackend: () => Promise<{
      status: "stopped" | "starting" | "ready" | "error";
      runtime: "java" | "python";
      port?: number;
      error?: string;
      executablePath?: string;
      serverPath?: string;
      diagnostics?: string[];
    }>;
    getBackendPort: () => Promise<number | null>;
    onBackendStatusChanged: (
      callback: (status: {
        status: "stopped" | "starting" | "ready" | "error";
        runtime: "java" | "python";
        port?: number;
        error?: string;
        executablePath?: string;
        serverPath?: string;
        diagnostics?: string[];
      }) => void,
    ) => () => void;
    // Settings
    getSettings: () => Promise<any>;
    setSetting: (key: string, value: any) => Promise<void>;
    // Projects
    getProjects: () => Promise<{ projects: any[]; lastProjectId?: string }>;
    createProject: (name: string, path: string) => Promise<any>;
    openProject: (id: string) => Promise<any>;
    renameProject: (id: string, newName: string) => Promise<any>;
    deleteProject: (id: string) => Promise<{ success: boolean }>;
    browseProjectFolder: () => Promise<{ canceled?: boolean; path?: string }>;
    switchProject: () => Promise<{ success: boolean }>;
    onProjectSelected: (callback: (project: any) => void) => () => void;
    // App info
    getAppVersion: () => Promise<string>;
    getPlatform: () => string;
    // Backend WebSocket auth
    getBackendWsToken: () => Promise<string>;
    onBackendWsToken: (callback: (token: string) => void) => () => void;
    // Logging
    getLogDir: () => Promise<string | null>;
    openLogDir: () => void;
    // Window chrome
    setTitleBarTheme: (isDark: boolean) => void;
    // Lifecycle
    signalRendererReady: () => void;
  };
}
