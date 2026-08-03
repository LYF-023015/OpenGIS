# opengis-framework

## 职责

提供可复用技术能力：JSON/JSONL、schema 校验、事件总线、并发/取消、配置、日志和原子写抽象，不包含业务编排。

## 依赖规则

- 允许依赖：`opengis-common`。
- 禁止依赖：Platform、AI、Agent、Tool、Workflow、GIS、Worker、Server。
- 入口标记：`org.opengis.framework.FrameworkModule`。
- 推荐包：`json`、`validation`、`event`、`concurrent`、`config`、`logging`。

## 测试

```powershell
./mvnw.cmd -pl opengis-framework -am test
```
