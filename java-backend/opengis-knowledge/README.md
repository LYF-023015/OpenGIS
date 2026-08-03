# opengis-knowledge

Knowledge 模块拥有 Context、Memory 和 Skill 语义。Phase 3 的 `MemoryStore` 兼容：

- `.opengis/memory/facts.jsonl`；
- `recipes.jsonl`；
- `datasets.jsonl`；
- `failures.jsonl`；
- 旧 `.opengis/memory.md`。

记录根据 `kind` 进入与 Python 相同的 JSONL 文件，读取时按 `max(last_used_at, created_at)` 排序。后续检索、压缩与 prompt projection 在 Agent/Knowledge 阶段继续扩展。
