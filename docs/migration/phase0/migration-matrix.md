# Phase 0 全量迁移矩阵说明

权威文件为 `docs/migration/migration-matrix.yaml`，由当前源代码和 Phase 0 冻结资产生成。本次共登记 347 项，不把“迁移后端”视为一个不可验证的大任务。

| 类别 | 数量 |
|---|---:|
| Tool | 89 |
| Backend → Renderer RPC | 56 |
| Renderer → Backend RPC | 47 |
| Event/notification | 46 |
| Python 语义消费者 | 44 |
| LLM Provider | 24 |
| 存储 | 17 |
| REST/WebSocket endpoint | 10 |
| 外部运行时 | 7 |
| 用户资产 | 7 |

每一行都有稳定 ID、来源、消费者、决策、Java 目标模块、迁移阶段和验证方式。Phase 1 以后应在该文件上增加负责人、状态、Java 实现位置和验证证据；不得另建一份互相漂移的总表。

Python 下线条件不是“Java 服务能启动”，而是所有保留项均达到 `verified`，所有废弃项均有用户迁移和 UI 清理证据，且外部运行时、用户资产和存储项都通过备份/回滚演练。
