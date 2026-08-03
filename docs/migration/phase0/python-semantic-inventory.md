# Phase 0 Python 语义消费者清单

扫描范围为 `src/`、`electron/`、构建配置、根 README 和既有 `docs/`。生成器识别 `pythonClient`、`python-backend`/venv、Python 脚本 UI、`rpc.code`、pip/requirements 等语义，并为每个命中文件记录行号样例、Java 目标和替代方案。

本次冻结共识别 44 个消费者文件：

| 类型 | 数量 | 替代方向 |
|---|---:|---|
| transport-client | 17 | 协议等价前保留 `pythonClient`；达到 Java parity 后重命名为中性的 `backendClient`。 |
| renderer-consumer | 15 | 保留 UI 行为，将 Python 专有标签、类型和能力判断替换为后端中性表达。 |
| electron-launcher | 5 | 使用 `BackendManager` 和 bundled JRE；迁移期保留显式兼容开关。 |
| documentation | 4 | 在对应阶段更新命令、架构图、扩展教程和故障排查。 |
| code-execution-ui | 3 | 先保持 `rpc.code.*` 协议，再把脚本语言、文件扩展名和提示从 Python 切为 Java。 |

权威清单是 `test/phase0/baseline/python-semantic-consumers.json`。该清单不能在 Phase 9 直接整体删除；每一项必须先由目标模块实现并验证，再把迁移矩阵中的状态推进。Phase 10 还需重新扫描，目标是生产代码、构建和安装包中的 Python 运行依赖为零。
