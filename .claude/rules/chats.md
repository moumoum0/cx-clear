---
paths:
  - "core/src/main/kotlin/dev/cxclear/chats/**"
  - "core/src/test/kotlin/dev/cxclear/chats/**"
  - "gui/src/main/kotlin/dev/cxclear/ui/components/Chats*.kt"
  - "gui/src/main/kotlin/dev/cxclear/ui/components/Retention*.kt"
  - "gui/src/main/kotlin/dev/cxclear/ui/components/Condition*.kt"
---

# 会话管理与自动清理

- 自动清理（`RetentionRunner.kt`）最后进入和手动删除同一个入口 `ChatDeleter.kt`，不另写删除路径。
- 扫描结果缓存在进程级 `ChatScanCache`；`autoRunDone` 保证自动清理每个进程只跑一次（否则删 → 重扫 → 再删成环）。删除成功后要 `invalidate()`。
- 空条件集的策略不命中任何会话（`RetentionMatchTest` 覆盖）。
- `RetentionStore` 的存储格式是 v2（`version=2` + `order=` + `rule.<id>.*`），读到 v1 自动迁移。
- 策略格式有三处：`RetentionStore` / `RetentionJson`、`cli/CliSchema.kt` 的平行副本、`RetentionAiPrompt.kt` 里给外部 AI 的格式说明。改格式三处一起改。
