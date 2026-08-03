# Phase 0 RPC and transport contract

## Frozen transport

- REST and WebSocket listen on loopback.
- WebSocket path: `/ws?token=<startup-token>`.
- JSON-RPC version: `2.0`.
- OpenGIS protocol model version: `3.0`.
- Startup stdout order: `OPENGIS_WS_TOKEN=...` then `OPENGIS_READY`.
- Requests contain `id`; notifications omit `id`.
- Standard errors use `-32700`, `-32600`, `-32601`, `-32602`, `-32603`; OpenGIS uses the `-32000` range.

## Captured surface

The generated inventory contains:

- 47 methods accepted by `RpcHandler._method_handlers`;
- 56 methods registered in the TypeScript inbound dispatcher;
- 46 additional observed server-push literals;
- 10 REST/WebSocket endpoint declarations.

The complete, sorted inventory is `test/phase0/rpc/method-inventory.json`. It is generated from the Python handler AST and TypeScript handler object keys, so it should be regenerated whenever either side changes.

`test/phase0/rpc/rpc-method-contracts.json` adds a source-derived portrait for every one of the 47 accepted methods: handler name and line, required and optional parameter keys, defaults/examples, result-field matchers and applicable `-32602`/`-32603` errors. It is deliberately marked conservative: manual validation and dynamically assembled results still require the representative transcript and, in Phase 2, a shared method-specific JSON Schema.

## Representative transcripts

`test/phase0/rpc/protocol-transcripts.jsonl` contains 20 deterministic cases covering:

- health and WebSocket authentication;
- parse error, invalid request, missing/unknown method;
- debug, tools, runs, profiles, sessions, permissions, operations, workers and skills;
- invalid parameters;
- backend-to-UI layer and MessagePart notifications;
- dynamic full + diff ordering.

The in-process replay validator passes all 17 request/response cases. The three server-push transcript rows contain four messages, all of which pass the JSON-RPC envelope schema. They are not dispatched into the real Renderer during the Python replay; TypeScript handler behavior remains covered by Vitest.

Dynamic IDs, workspaces and types use matchers such as `${WORKSPACE}` and `{"$type":"array"}`. A Java transcript runner must substitute/match these markers instead of comparing unstable values literally.

## Schema

`test/phase0/rpc/json-rpc-envelope.schema.json` freezes the common request/notification, success and error envelope. Phase 0 also freezes generated per-method examples/matchers; Phase 2 should promote them into a shared, hand-reviewed contract module with method-specific JSON Schemas.

## Known contract inconsistencies

- Backend methods `user_instructions.get/set` do not use the otherwise standard `rpc.`, `chat.` or `event.` prefix.
- TypeScript currently registers 56 handlers while one test still asserts 53.
- Python serializes some dictionaries with spaces while TypeScript often emits compact JSON; semantic JSON comparison is required.
- Paths exposed over RPC should use `/` as protocol separators even on Windows; current Python tests prove this is not consistently enforced.
