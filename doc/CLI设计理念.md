# CLI 设计理念

## 核心思路

CLI 不是 GUI 的简化版，是给 AI 和自动化用的接口。AI 不会"打开界面看一眼再决定删不删"——它需要一次调用就能完成查找、统计、删除的完整流程。

### 查找-统计-删除三元组

每个用户意图都可以拆成三步：
1. **查找**：匹配符合条件的目标（时间、大小、类型）
2. **统计**：看看匹配了多少、占多大空间
3. **删除**：执行清理

GUI 里这三步是分离的（先扫描、看结果、勾选、点清理），但 CLI 必须让 AI 能在一条命令里走完全程。

### 参数互通

同一个筛选条件（如 `--older-than 30d`）在所有命令里都能用，不分"查找专用"还是"删除专用"。AI 可以先用 `find chats --older-than 30d` 预览，确认无误后直接把 `find` 改成 `delete`，参数原样复制。

### 扫描是预设规则的特殊查找

`scan` 不是独立的命令类型，本质是"用项目预设的清理规则去查找文件"。它和 `find files --type cache` 没区别，只是规则来源不同（一个是代码里写死的 `CleanTarget`，一个是用户参数）。

---

## 命令结构

```
cxclear find <目标> [筛选条件]       # 查找并统计，不删除
cxclear delete <目标> [筛选条件]     # 删除匹配的目标
cxclear scan [筛选条件]              # 用预设规则扫描（特殊的 find）
cxclear clean [筛选条件]             # 清理扫描结果（特殊的 delete）
cxclear status [筛选条件]            # 显示占用和会话数
cxclear history [筛选条件]           # 显示清理历史
```

### find / delete 的目标类型

- `chats` - 对话记录
- `files` - 文件（按清理项分类）

---

## 参数说明

### 通用筛选参数

所有 `find` / `delete` / `scan` / `clean` 命令都支持这些参数。

#### `--tool <id>`
指定工具，多个用逗号分隔。

**示例**：
```bash
cxclear find chats --tool cursor
cxclear delete files --tool cursor,codex
```

**可选值**：`codex`, `claude`, `cursor`, `opencode`, `deepseek-hermes`

---

#### `--type <type>`
文件类型关键字（只对 `files` 目标有效）。

匹配 `CleanTarget.id` 包含该关键字的清理项。例如 `--type cache` 会匹配 `codex_cache`、`cursor_cache_runtimes` 等。

**示例**：
```bash
cxclear find files --type cache
cxclear delete files --type downloads
```

**常用值**：`cache`, `logs`, `downloads`, `sandbox`, `plugins`, `runtime`

---

#### `--older-than <duration>`
只匹配早于指定时间的目标（会话用 `updatedMillis`，文件用修改时间）。

**格式**：数字 + 单位（`d` 天 / `h` 小时 / `m` 分钟）

**示例**：
```bash
cxclear find chats --older-than 30d      # 30 天前的对话
cxclear delete files --older-than 7d     # 7 天前修改的文件
```

---

#### `--newer-than <duration>`
只匹配晚于指定时间的目标（与 `--older-than` 相反）。

**示例**：
```bash
cxclear find chats --newer-than 7d       # 最近 7 天的对话
```

---

#### `--size-gt <size>`
只匹配大于指定大小的目标。

**格式**：数字 + 单位（`KB` / `MB` / `GB`）

**示例**：
```bash
cxclear find files --size-gt 100MB       # 大于 100MB 的文件
cxclear delete files --type downloads --size-gt 1GB
```

---

#### `--size-lt <size>`
只匹配小于指定大小的目标。

**示例**：
```bash
cxclear find files --size-lt 10MB        # 小于 10MB 的文件
```

---

### 会话保留参数

只对 `chats` 目标有效。这些参数在匹配后再排除一部分结果，用于"删除旧对话但保留最近的"场景。

#### `--keep-recent <n>`
从匹配结果中排除最近的 N 条会话。

**示例**：
```bash
# 删除 90 天前的对话，但保留最近 10 条
cxclear delete chats --older-than 90d --keep-recent 10 --preview
```

---

#### `--keep-days <n>`
从匹配结果中排除最近 N 天内的会话。

**示例**：
```bash
# 删除所有对话，但保留最近 7 天的
cxclear delete chats --keep-days 7 --preview
```

---

### 执行控制参数

#### `--preview`
只显示删除计划，不实际删除（只对 `delete` / `clean` 有效）。

**示例**：
```bash
cxclear delete chats --older-than 30d --preview
```

---

#### `--yes`
跳过确认直接执行（默认删除前会要求确认）。

**示例**：
```bash
cxclear clean --safe-only --yes
cxclear delete chats --older-than 90d --yes
```

---

#### `--json`
输出 JSON 格式（默认是人类可读的表格）。

**示例**：
```bash
cxclear find chats --older-than 30d --json
```

---

### 特定命令的参数

#### `--safe-only`
只包含 `Risk.SAFE` 的清理项（`scan` / `clean` 专用）。

**示例**：
```bash
cxclear scan --safe-only
cxclear clean --safe-only --yes
```

---

#### `--targets <id,id>`
精确指定清理项 ID（`clean` 专用）。

**示例**：
```bash
cxclear clean --targets codex_cache,cursor_logs --yes
```

---

#### `--limit <n>`
限制结果数量（`history` 专用，默认 10）。

**示例**：
```bash
cxclear history --limit 20
```

---

## 使用场景示例

### 场景 1：用户想清理旧对话

**对话**：
> 用户："帮我看看哪些对话记录已经不需要了"

**AI 执行**：
```bash
# 先查找 30 天前的对话
cxclear find chats --older-than 30d --json

# 确认后删除，但保留最近 10 条
cxclear delete chats --older-than 30d --keep-recent 10 --preview
cxclear delete chats --older-than 30d --keep-recent 10 --yes
```

---

### 场景 2：用户想清理缓存

**对话**：
> 用户："磁盘快满了，清理一下缓存"

**AI 执行**：
```bash
# 查看缓存占用
cxclear find files --type cache --json

# 清理大于 100MB 的缓存
cxclear delete files --type cache --size-gt 100MB --preview
cxclear delete files --type cache --size-gt 100MB --yes
```

---

### 场景 3：用户想定期自动清理

**对话**：
> 用户："每周自动清理安全项"

**AI 生成定时任务**：
```bash
# Windows 任务计划程序调用
cxclear clean --safe-only --yes --json
```

---

### 场景 4：用户想清理特定工具

**对话**：
> 用户："Cursor 的下载缓存太大了"

**AI 执行**：
```bash
# 查看 Cursor 下载缓存
cxclear find files --tool cursor --type downloads --json

# 删除大于 500MB 的
cxclear delete files --tool cursor --type downloads --size-gt 500MB --yes
```

---

## 设计约束

1. **参数尽量互通**：同一个筛选条件在 `find` / `delete` / `scan` / `clean` 里都能用，AI 可以无缝切换命令而不改参数。

2. **默认安全**：删除命令默认要确认（除非 `--yes`），`--preview` 只显示计划不执行。

3. **输出分离**：JSON 结果走 `stdout`，人类消息（进度、警告）走 `stderr`，方便 AI 解析。

4. **无状态**：每次调用都是独立的，不依赖上次扫描结果（`clean` 内部会自动重新扫描）。

5. **复用 GUI 逻辑**：`find` / `delete` 复用 `Scanner` / `Cleaner` / `ChatDeleter`，不重复实现路径展开和删除校验。
