# ADR-0012：Phase 10 采用 Windows-only 发布与 Python 可恢复冻结

- 状态：Accepted
- 日期：2026-08-03
- 范围：Phase 10 及后续桌面发布

## 背景

Phase 9 原计划包含 Windows、macOS 和 Linux 的真实安装验收。用户已明确 OpenGIS 只在 Windows 上使用，同时要求 Java 成为实际运行版本、Python 版本永久作为备份保留。

## 决策

1. 当前支持矩阵只有 Windows x64；CI、electron-builder 和验收清单不再要求 macOS/Linux。
2. Windows 安装包只包含 Electron、bundled jlink JRE、Java Server JAR 和静态资源，不包含 Python 源码、解释器或 Python Manager。
3. `python-backend/` 永久保留，用 checksum、依赖快照、恢复说明和独立 Git 树标签冻结；不参与 Java 故障自动回退。
4. 原“两个发布周期”在当前本机交付中落实为两个隔离、可重复的 Windows 候选版启动周期：首次安装启动，以及带旧 `last-good` 状态的升级启动。结果不得表述为长期线上遥测。
5. SBOM 无法从本地 POM 自动确定的许可证保持 `NOASSERTION`，不猜测许可证；用户于 2026-08-03 明确决定不进行人工许可证复核，因此该项记录为已接受风险，不再阻断 Windows 发布。

## 结果

- 用户只需学习和维护一条 Windows + Java 生产链。
- Python 历史实现仍可校验、导出和显式恢复，但生产包不会加载它。
- 若未来重新支持 macOS/Linux，必须新增对应平台的 JRE、打包、签名和真机验收，不能沿用本 ADR 的 Windows 证据。
