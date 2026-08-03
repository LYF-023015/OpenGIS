# opengis-server

## 职责

这是唯一可执行 Java 模块，也是 Spring Boot Composition Root。它负责进程入口、REST/WebSocket/JSON-RPC 传输、Bean 装配、stdout 启动契约和优雅停止。Agent、Tool 或 GIS 业务规则必须进入所属领域模块，不能堆在 Controller 中。

## Phase 2 包结构

```text
org.opengis.server
├─ OpenGisApplication
├─ config/                    # 运行参数
├─ lifecycle/                 # 启停状态机和进程级 WebSocket token
├─ health/                    # GET /api/health
├─ rpc/
│  ├─ RpcHttpController       # POST /api/rpc
│  ├─ RpcDispatcher           # 解析、校验、调用和错误映射
│  ├─ RpcMethodRegistry       # 线程安全 method → handler 表
│  ├─ CoreRpcMethods          # ping 和迁移占位方法
│  └─ LegacyMethodCatalog     # Phase 0 冻结的 47 个 Python 方法
└─ transport/
   ├─ WebSocketConfig / OpenGisWebSocketHandler
   ├─ RpcConnectionManager / RpcConnection
   ├─ UiRpcGateway            # Java → Renderer API
   └─ DynamicLayerUpdateBuffer
```

## 跟踪一次请求

Renderer 调用 Java：

```text
WebSocket /ws?token=...
  → OpenGisWebSocketHandler
  → RpcDispatcher
  → RpcMethodRegistry
  → RpcHandler
  → JSON-RPC response
```

Java 调用 Renderer：

```text
领域服务
  → UiRpcGateway.request(...)
  → RpcConnection 创建 UUID 并登记 CompletableFuture
  → Renderer handler 返回相同 id
  → RpcConnection 完成 future 并删除 pending
```

连接关闭时，该连接的所有 pending future 都以 `RpcConnectionClosedException` 失败并立即清空，防止内存泄漏和永远等待。

## 当前接口

- `GET /api/health`：进程健康状态。
- `POST /api/rpc`：JSON-RPC HTTP bridge；notification 返回 HTTP 204。
- `WS /ws?token=<process-token>`：双向 JSON-RPC；错误 token 返回 `-32001` 后关闭。
- `rpc.system.ping`：返回 `status=ok`、`protocol_version=3.0`、`runtime=java`。

47 个旧 Python 方法已进入 registry。尚未迁移的业务方法返回 `-32004 Capability not migrated` 并携带 `method` 和 `planned_phase`；完全未知的方法返回标准 `-32601`。

## 动态图层规则

`rpc.ui.map.dynamic_layer_update` 在 100 ms 帧窗口内按 `connection + layer_id` 缓冲：

- 新 full 丢弃同图层更旧的 full/diff；
- full 之后的每个 diff 全部保留；
- diff 按接收顺序发送；
- 连接关闭时删除该连接的未发送帧。

## 命令

```powershell
./mvnw.cmd -pl opengis-server -am test
./mvnw.cmd verify
./opengis-server/scripts/build-runtime.ps1
```

Java Sidecar 继续兼容 Python 的 `--host`、`--port`、`--log-dir` 参数，并按顺序输出 `OPENGIS_WS_TOKEN=...` 与 `OPENGIS_READY`。
