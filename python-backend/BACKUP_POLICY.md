# Python Backend 备份保留策略

## 决策

`python-backend/` 永久保留，不在 Java 迁移完成后删除。

OpenGIS 的正常开发、启动、生产打包和用户请求将以 `java-backend/` 为主运行时。Python 目录用于：

- 查询迁移前的原始业务语义；
- 运行隔离的 Python/Java 差异测试；
- 审计旧 Script、Operation、Worker 和 Workflow；
- Java 出现灾难性问题时，按明确恢复步骤临时恢复旧版本；
- 为后续算法研究保留历史实现。

## Java 切换完成后的规则

1. Electron 正常启动不得自动创建 Python 环境、运行 pip 或启动 Python Sidecar。
2. Java 生产代码不得导入、调用或静默回退到 `python-backend/`。
3. Python 源码、requirements/lock、fixture、测试和迁移报告继续纳入 Git。
4. `.venv`、缓存、构建产物不属于备份；环境应通过固定依赖清单重新创建。
5. 冻结时创建 Git tag、目录 checksum、依赖快照和恢复说明。
6. 如需临时恢复 Python，必须由维护者显式启用并记录原因，不能作为 Java 请求失败后的自动 fallback。

## 当前状态

Phase 10 已于 2026-08-03 按 Windows-only 产品范围完成冻结：Java 是开发默认值和唯一生产运行时，Python 不进入安装包，也不是自动 fallback。

本目录由 `SHA256SUMS`、`BACKUP_DEPENDENCIES.txt`、`BACKUP_MANIFEST.json` 和 `RECOVERY.md` 共同描述；Git 树标签为 `python-backend-phase10-windows-20260803`。任何后续修改都必须创建新的备份版本和校验清单，禁止删除本目录。
