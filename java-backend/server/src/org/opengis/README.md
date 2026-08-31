# 后端源码怎么读

这里是唯一 Spring Boot 业务模块，顶层只保留六个有明确含义的包。

| 包 | 主职责 |
| --- | --- |
| `server` | 启动、配置、RPC、WebSocket/HTTP 与应用用例装配 |
| `assistant` | Agent 循环、会话、模型 Provider、Context 与 Memory |
| `tool` | Tool 协议、权限、注册、执行与内置插件适配 |
| `gis` | GIS 数据源、矢量、栅格、CRS 与空间运算 |
| `automation` | Workflow、Worker、队列与隔离 Java 执行 |
| `core` | 协议、持久化、安全、并发和插件内核 |

推荐调用链：`OpenGisApplication` → `server/rpc` → `server/*ApplicationService` → 所属领域的主 Service/Runtime → `core` 基础设施。不要从文件列表中随机阅读。
