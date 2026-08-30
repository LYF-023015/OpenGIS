# OpenGIS Java 后端

`java-backend/` 是 OpenGIS 唯一的后端工程根目录。应用与测试均只启动 Java，不再包含 Python sidecar 或 Python backend 回退链路。

## 推荐学习顺序

1. 阅读根目录 `pom.xml`，理解 Maven Reactor、Java 21 和统一质量插件。
2. 阅读 `opengis-common/README.md`，理解稳定协议为什么位于最底层。
3. 阅读 `docs/migration/phase2/README.md`，理解 HTTP、WebSocket 和双向 JSON-RPC。
4. 阅读 `docs/migration/phase3/README.md`，理解 workspace 与持久化兼容层。
5. 阅读 `opengis-tool/README.md` 和 `docs/migration/phase4/README.md`，沿一次 ToolCall 学习注册、校验、权限、执行、事件和 Artifact。
6. 最后阅读 `opengis-server`，观察组合根怎样把框架无关模块接到 Spring 和 Renderer。

## 工程结构

```text
java-backend/
├─ pom.xml                         # 聚合父 POM、版本与质量门禁
├─ mvnw / mvnw.cmd / .mvn/         # 固定 Maven 3.9.10
├─ config/checkstyle/              # 统一静态检查
├─ opengis-common/                 # JSON Schema、DTO、错误码等稳定契约
├─ opengis-framework/              # 通用技术基础设施
├─ opengis-platform/               # Workspace、文件、Git、迁移
├─ opengis-ai/                     # Spring AI Provider 适配与稳定 LLM 边界
├─ opengis-knowledge/              # Context、Memory、Skill
├─ opengis-tool/                   # Tool SPI、Registry、权限和 Runtime
├─ opengis-agent/                  # ChatClient/Advisor 编排、Session、Telemetry
├─ opengis-workflow/               # Workflow DAG 与 child session
├─ opengis-gis/                    # GIS、Raster、Operation
├─ opengis-worker/                 # 独立 JVM Worker
└─ opengis-server/                 # Spring Boot 组合根和传输层
```

依赖方向始终是 `server → 领域模块 → platform/framework → common`。底层模块不能反向依赖上层，`ModuleDependencyTest` 自动检查该规则。

### Controller、Service 与 Repository 在哪里

本工程按业务模块组织代码，但仍然保留常见的职责分层：

- `opengis-server/transport` 和 `opengis-server/rpc` 是接口适配层，相当于 REST 项目中的 Controller。RPC 类按照 `AgentRpcMethods`、`WorkflowRpcMethods`、`GisRpcMethods` 等业务职责命名。
- 各业务模块及 `opengis-server` 中的 `*Service` 是应用与领域服务层。领域模块尽量保持纯 Java，由 `opengis-server/config` 统一注册为 Spring Bean。
- 各模块中的 `*Repository`、`*Store`、`*Archive` 是持久化层。当前主要存储到 Workspace JSON/JSONL 文件，而不是 JPA 数据库。
- `docs/migration/phase*/` 和兼容测试数据中的阶段编号只记录迁移历史；生产类、包和配置不再使用 `PhaseX` 命名。

## 当前完成度

- Phase 1：11 模块骨架、生命周期、health、日志、jlink runtime、Electron 启停冒烟。
- Phase 2：共享协议 Schema、JSON-RPC、token WebSocket、双向调用、HTTP bridge、pending 清理和动态图层合并。
- Phase 3：持久化兼容读写、RunArchive、Electron 数据升级、Git 快照回滚和 migration manifest。
- Phase 4：统一 Tool 模型/Registry/Schema/权限/审批/取消/事件/Artifact；激活 62 个真实工具和 RPC，27 个跨阶段能力有明确后续归属。
- Phase 5：Provider-neutral LLM 与 Agent 主循环、Context/Memory、取消和 MessagePart。
- Phase 6：持久化 Queue、Workflow schema v2、安全条件、DAG child session、恢复与幂等。
- Phase 7：完整 Java GIS、Raster、OSM/QGIS 和 datasource adapters。
- Phase 8A/B/C：Operation v2、Java Script SDK/Runner 和 Java Worker 全生命周期。
- Phase 9：Electron/Renderer 默认 Java、通用 BackendManager、结构化 Pivot、bundled JRE 和无 Python 生产包；Windows-only 发布范围已验收。
- Phase 10：迁移台账终态、Windows SBOM/许可证/checksum、生产 Python 移除和双轮候选版验证均已完成。
- Spring AI 升级：Spring AI 2.0 接管 OpenAI/Anthropic 模型适配与 ToolCallingAdvisor 循环；OpenGIS 保留 Session、RuntimeControl、ToolRuntime、RunArchive 和 UI 事件边界。

## 常用命令

在 `java-backend/` 执行：

```powershell
./mvnw.cmd spotless:apply
./mvnw.cmd verify
./opengis-server/scripts/build-runtime.ps1
```

在仓库根目录执行：

```powershell
npm run typecheck
npm run test:phase4-renderer
npm run smoke:java-sidecar
npm run audit:phase10
```

构建输出位于各模块 `target/`，不提交 Git。
