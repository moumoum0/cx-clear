---
paths:
  - "cli/**"
  - "packaging/cli_launcher.cpp"
---

# CLI

设计背景见 `doc/CLI设计理念.md`。

- `CliJsonViews.kt` 的字段名是外部契约，改名等于破坏调用方。
- `CliSchema.kt` 里的策略格式是 `RetentionStore` / `RetentionJson` 的平行副本，改一边要同步另一边。
- 文件 / 会话的筛选与删除（delete / clean）共用 `CliPipelines.kt`，新命令不另写删除。
- CLI 有意不提供策略写入：`rules put` 返回用法错误（`CliArgsTest` 覆盖），只有 `rules get` / `rules validate`。
- 发布包里 CLI 走 `cxclear.exe`（`packaging/cli_launcher.cpp`），不走 GUI 子系统的 `CX Clear.exe`——后者 stdout 管道常是空的，AI 拿不到输出；启动器内部再用 `java.exe` 跑 `app/cli-*.jar`。
