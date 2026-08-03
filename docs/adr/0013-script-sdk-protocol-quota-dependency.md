# ADR-0013：Script SDK、父子协议、资源配额与依赖解析

- 状态：Accepted
- 日期：2026-08-02

## 决策

发布无 Spring/GIS 实现依赖的 `opengis-script-sdk`，协议版本从 1.0 开始。依赖只接受固定 Maven release 坐标、显式审批和 allowlist group，缓存记录 SHA-256、repository 与可发现 license；默认离线。

## 后果

用户代码只依赖小而稳定的 SDK，服务端实现可独立演进。依赖无法批准、缓存缺失、checksum 不符、协议版本不兼容或超出配额时必须明确失败，不使用隐藏 fallback。
