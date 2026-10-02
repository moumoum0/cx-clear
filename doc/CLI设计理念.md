# CLI 设计理念

## 核心思路

CLI 不是 GUI 的简化版，是给 AI 和自动化用的接口。AI 不会"打开界面看一眼再决定删不删"——它需要一次调用就能完成查找、统计、删除的完整流程。

### 查找-统计-删除三元组

每个用户意图都可以拆成三步：
1. **查找**：匹配符合条件的目标（时间、大小、类型）
2. **统计**：看看匹配了多少、占多大空间
3. **删除**：执行清理

GUI 里这三步是分离的（先扫描、看结果、勾选、点清理），但 CLI 必须让 AI 能在一条命令里完成全程。

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

`--tool`、时间和大小筛选在 `find` / `delete` / `scan` / `clean` 中互通。
`--type` 适用于文件命令（包括 `scan` / `clean`），会话保留参数只适用于 `chats`。

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
文件类型关键字（适用于 `find files` / `delete files` / `scan` / `clean`）。

匹配 `CleanTarget.id` 包含该关键字的清理项。例如 `--type cache` 会匹配 `codex_cache`、`cursor_cache_runtimes` 等。

**示例**：
```bash
cxclear find files --type cache
cxclear delete files --type downloads
```

**常用值**：`cache`, `logs`, `downloads`, `sandbox`, `plugins`, `runtime`

---

#### `--older-than <duration>`
只匹配早于指定时间的会话（使用 `updatedMillis`）或文件（使用扫描快照的修改时间）。

文件时间筛选逐文件作用于冻结的删除计划，不以目录修改时间判断整个目录；未命中的文件、目录和链接均保留。

**格式**：数字 + 单位（`d` 天 / `h` 小时 / `m` 分钟 / `s` 秒）

**示例**：
```bash
cxclear find chats --older-than 30d      # 30 天前的对话
cxclear delete chats --older-than 7d     # 7 天前更新的对话
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
只匹配大于指定大小的目标。会话按每条会话的总大小筛选；文件命令按每个清理项的总大小筛选，并非按单个文件大小。若同时指定时间范围，先筛选文件时间，再按命中文件的合计大小筛选清理项。

**格式**：数字 + 单位（`B` / `KB` / `MB` / `GB` / `TB`），支持小数；省略单位时按字节计算。

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
从匹配结果中排除最近的 N 条会话。匹配数量小于或等于 N 时全部保留；N 为 0 时不保留。保留数量在所选工具的匹配结果中合并计算。

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

### 策略筛选参数

只对 `chats` 目标有效。把自动清理策略当成筛选来源，和其他条件是交集关系；只计算已启用的规则，与自动清理的实际执行一致。

#### `--rules`
用已保存的策略（`chat-retention.txt`）匹配会话。

**示例**：
```bash
# 预览已保存策略会命中哪些会话
cxclear find chats --rules

# 按已保存策略执行删除
cxclear delete chats --rules --yes
```

---

#### `--rules-file <path>`
用一份未写入配置文件的策略 JSON 匹配会话，用于验证 AI 生成的候选规则。值为 `-` 时从标准输入读取。

**示例**：
```bash
# 试跑一份候选策略，看实际会删谁
cxclear find chats --rules-file rules.json
cxclear find chats --rules-file - < rules.json
```

---

#### `--rule <id,id>`
只使用指定 id 的规则，多个用逗号分隔。用于预览单条规则：挑中的规则无论是否启用都参与匹配，方便在 GUI 中启用前验证。不指定 `--rules-file` 时从已保存策略中挑选。

**示例**：
```bash
# 只看 rule-3 这一条会删什么（即使它还没启用）
cxclear find chats --rule rule-3

# 在候选文件里单测某条规则
cxclear find chats --rules-file rules.json --rule new-rule
```

---

### 执行控制参数

#### `--preview`
只显示计划，不实际删除（适用于 `delete` / `clean`）。

**示例**：
```bash
cxclear delete chats --older-than 30d --preview
```

---

#### `--yes`
执行删除。默认只返回预览，不等待交互确认；同时指定 `--preview` 时仍只预览。

**示例**：
```bash
cxclear clean --safe-only --yes
cxclear delete chats --older-than 90d --yes
```

---

#### `--json`
兼容选项。所有命令始终输出 JSON，不带此参数时输出格式相同。

**示例**：
```bash
cxclear find chats --older-than 30d --json
```

---

### 特定命令的参数

#### `--safe-only`
只包含 `Risk.SAFE` 的清理项（适用于 `find files` / `delete files` / `scan` / `clean`）。

**示例**：
```bash
cxclear scan --safe-only
cxclear clean --safe-only --yes
```

---

#### `--targets <id,id>`
精确指定清理项 ID（适用于 `clean` / `delete files`）。

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

2. **默认安全**：删除命令默认只返回预览，只有显式指定 `--yes` 才执行；`--preview` 始终只显示计划。

3. **输出分离**：JSON 结果输出到 `stdout`，人类消息（进度、警告）输出到 `stderr`，方便 AI 解析。

4. **无状态**：每次调用都是独立的，不依赖上次扫描结果（`clean` 内部会自动重新扫描）。

5. **复用 GUI 逻辑**：`find` / `delete` 复用 `Scanner` / `Cleaner` / `ChatDeleter`，不重复实现路径展开和删除校验。

## 自动清理策略

AI 按设置页提供的规则提示词直接修改 `%USERPROFILE%\.cxclear\chat-retention.txt`，新建规则默认关闭，在 GUI 中核对后启用。CLI 不提供规则写入命令。

`rules get` 读取现有配置并输出 JSON；`rules validate --file <path>` 校验规则 JSON，也可从标准输入读取，不修改配置。JSON 是读取和校验接口的格式，不是配置文件的存储格式。验证策略的实际命中用 `find chats --rules`（已保存策略）或 `find chats --rules-file`（候选 JSON）。
