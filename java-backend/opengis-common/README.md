# opengis-common

## 职责

这是依赖图的最底层，只保存跨模块稳定契约，不包含 Spring Bean、数据库或网络实现。Phase 2 的协议唯一真源位于：

```text
src/main/resources/opengis/protocol/opengis-protocol-3.0.schema.json
```

Schema 固化了 TypeScript `src/types/protocol.ts` 和 Python `runtime/protocol_types.py` 中的 JSON-RPC、BBox、CRS、GeometryType、LayerSource 与 LayerStyle。

## 包结构

```text
org.opengis.common
├─ CommonModule
└─ protocol
   ├─ ProtocolVersion
   ├─ JsonRpcErrorCodes
   ├─ JsonRpcRequest / JsonRpcNotification
   ├─ JsonRpcSuccessResponse / JsonRpcErrorResponse
   └─ GeometryType / LayerSource / LayerStyleType / LayerStyle
```

## 依赖规则

- 允许：JDK 与纯契约库。
- 禁止：Spring、磁盘/网络实现、Agent、Tool、GIS 和 Provider SDK。
- 上层可以依赖 common，common 不能了解任何上层模块。

## 验证

```powershell
./mvnw.cmd -pl opengis-common -am test
```

Java、TypeScript 和 Python 三端都有 Schema 对齐测试。修改协议时必须同步三端测试，不能单独改某一侧。
