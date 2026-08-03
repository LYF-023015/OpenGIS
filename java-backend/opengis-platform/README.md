# opengis-platform

## 职责

平台模块承载与业务无关的操作系统能力：安全路径、UTF-8 JSON/JSONL、原子文件替换、Electron userData 兼容、Git 进程适配和 workspace migration。

## Phase 3 包结构

```text
org.opengis.platform
├─ persistence/
│  ├─ WorkspaceLayout
│  ├─ JsonFileStore
│  └─ WorkspaceCompatibilityReader
├─ electron/ElectronDataStore
├─ git/GitWorkspaceAdapter
└─ migration/WorkspaceMigrationService
```

`JsonFileStore` 对 snapshot JSON 使用“同目录临时文件 → 原子替换”，对 JSONL 使用 append；所有文本固定 UTF-8。`WorkspaceLayout` 拒绝绝对路径与 `..` 逃逸。

依赖方向：`platform → framework/common`。领域模块可以复用平台能力，平台模块不能认识 Agent、Workflow、GIS 或 Server。
