# 后端六个领域边界

OpenGIS 后端是一个 Spring Boot 模块化单体，不再为每个领域维护重复 Maven 目录。

| 顶层包 | 内部能力 | 依赖边界 |
| --- | --- | --- |
| `org.opengis.core` | JSON-RPC 协议、插件内核、并发取消、Workspace 持久化、安全、Git、迁移 | 只能依赖通用库 |
| `org.opengis.assistant` | Agent Loop、会话、运行归档、LLM 模型与 Provider、Context、Memory | 可依赖 core、tool |
| `org.opengis.gis` | 文件 IO、CRS、矢量、栅格、OSM/QGIS/Datasource、Operation | 可依赖 core、automation |
| `org.opengis.automation` | Workflow schema/DAG/Queue、Worker 生命周期、Java Code Runtime | 可依赖 core、tool |
| `org.opengis.tool` | Tool API、Schema、权限、Registry、Runtime、内置工具与跨领域插件 | 可适配 assistant/automation/gis |
| `org.opengis.server` | Spring Boot 组合根、RPC、传输、健康检查和应用服务 | 可装配所有领域 |

关键规则：未知 Java 代码只在隔离子 JVM 中运行；Agent 工具调用必须经过 `ToolRuntime`；GIS 算法不反向依赖 Agent；跨领域适配器放在 `org.opengis.tool.plugins`；Spring Controller 与网络传输只位于 `org.opengis.server`。

生产源码、测试与资源分别位于 `server/src`、`server/test`、`server/resources`。完整依赖规则由 `ModuleDependencyTest` 持续验证。
