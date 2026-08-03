# ADR-0011：桌面端使用 bundled Java，Python 作为源码备份保留

- 状态：Accepted
- 日期：2026-08-03
- 范围：Phase 9～10

## 背景

迁移前 Electron 会在首次启动时下载 Python、创建用户目录 venv、运行 pip，并直接管理 Python Sidecar。该模式使干净机器启动依赖网络和本机环境，也让 Renderer、设置页和安装包长期绑定 Python。

同时，原 Python 实现仍具有历史语义、差异验证和灾难恢复价值，不能因为 Java 成为主运行时就删除。

## 决策

1. 开发和生产默认运行时均为 Java；生产包只能选择 Java。
2. 每个平台通过 jdeps + jlink 构建本平台 JRE，与 `opengis-server.jar` 一起打包。
3. Electron 只通过 `BackendManager` 和 `backend:*` IPC 暴露生命周期，Renderer 只依赖 `backendClient`。
4. `OPENGIS_BACKEND=python` 仅在未打包的开发环境有效，并要求维护者事先显式准备环境；不执行自动安装。
5. `python-backend/` 永久保留在 Git 中，但不进入生产包、不被正常启动链导入，也不能成为 Java 请求失败后的自动 fallback。
6. 启动失败时显示 Retry/Open Logs/Quit，记录最后一次成功 Java JAR checksum；失败流程不得写入 workspace。

## 结果

- 用户无需安装 Python 或系统 JDK。
- 安装包变大，但运行环境固定、诊断路径清晰。
- 当前产品只发布 Windows x64，JRE 必须在 Windows 发布流水线中构建；macOS/Linux 不属于当前验收范围。
- Python 恢复属于显式维护操作，不属于应用高可用回退机制。
- 正式发布签名在发布流水线配置；Phase 9 本地验收包保持 unsigned。
