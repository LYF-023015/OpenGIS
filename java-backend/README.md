# OpenGIS Java 后端

后端保留 Java 21、Spring Boot 和 Spring AI，并收敛为模块化单体：`server` 是唯一应用和部署单元，`script-sdk` 是隔离子 JVM 使用的独立契约。

```text
java-backend/
├─ server/
│  ├─ src/org/opengis/
│  │  ├─ core/          # 协议、持久化、安全、并发、插件内核
│  │  ├─ assistant/     # Agent、LLM Provider、Context、Memory
│  │  ├─ gis/           # GIS 数据、算法和外部适配器
│  │  ├─ automation/    # Workflow、Worker、Java Code Runtime
│  │  ├─ tool/          # Tool API、权限、执行与插件适配
│  │  └─ server/        # Spring Boot、RPC、传输和应用服务
│  ├─ resources/
│  ├─ test/
│  └─ test-resources/
└─ script-sdk/
   └─ src/org/opengis/script/sdk/
```

日常阅读从 `server/OpenGisApplication.java` 或具体 `server/rpc/*RpcMethods.java` 进入，再跟到一个领域包。Maven 显式使用扁平的 `server/src` 与 `server/test`，因此无需重复展开 `src/main/java`。

边界规则由 ArchUnit 检查：`core` 不依赖业务域；`server` 负责装配；`tool.plugins` 承担跨领域适配；领域算法不放进插件入口。详见 [`docs/backend-domains.md`](docs/backend-domains.md) 与 [`../docs/architecture/plugin-architecture.md`](../docs/architecture/plugin-architecture.md)。

```powershell
./mvnw.cmd test
./mvnw.cmd -DskipTests package
```
