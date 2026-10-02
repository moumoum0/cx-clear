# CLI 文档

CLI 主要给 AI 调用：stdout 只输出一行 JSON，给人看的报错文字走 stderr。

## 安装

### npm（Windows x64）

需要 Node.js 22 或以上：

```powershell
npm install -g @moumoum/cxclear
cxclear --version
```

### PowerShell 安装脚本

```powershell
irm https://raw.githubusercontent.com/moumoum0/cx-clear/main/install.ps1 | iex
```

```powershell
$env:CXCLEAR_SILENT = '1'; irm https://raw.githubusercontent.com/moumoum0/cx-clear/main/install.ps1 | iex
```

## 设计理念

- **查询→预览→删除**：先 `find` 看匹配什么，再 `delete` 预览计划，最后加 `--yes` 执行
- **参数继承**：同一筛选条件在 `find` 和 `delete` 通用
- **默认安全**：不带 `--yes` 只预览；带了 `--preview` 也一定不删
- **每次调用独立**：不依赖上次扫描结果。例外：不写 `--tool` 时，文件类命令和 `status` 读 GUI 设置里的默认工具

## 命令一览

| 命令 | 说明 | 可用参数 |
|------|------|----------|
| `find chats` | 查找会话 | 会话筛选、`--rules` |
| `delete chats` | 删除会话 | 会话筛选、`--rules`、`--yes`、`--preview` |
| `find files` | 查找可清理文件 | 文件筛选、`--safe-only` |
| `delete files` | 删除匹配的清理项 | 文件筛选、`--safe-only`、`--targets`、`--yes`、`--preview` |
| `scan` | 各工具占用 + 可清理项 | 文件筛选、`--safe-only` |
| `clean` | 清理默认勾选项，或 `--targets` 指定的项 | 同 `delete files` |
| `status` | 各工具占用与会话数 | `--tool` |
| `history` | 清理历史 | `--limit <n>`（默认 10） |
| `rules get` | 读取已保存的自动清理策略 | — |
| `rules validate` | 校验策略 JSON，不写入 | `--file <path>`（不给或 `-` 读标准输入） |
| `schema` | 机器可读的完整定义（命令、参数、退出码、工具 id） | — |
| `help` / `-h` / `--help` | 用法与示例 | — |
| `version` / `-v` / `--version` | 版本号 | — |

所有命令都接受 `--json`（兼容用，写不写输出都一样）。传了命令不支持的参数会报错（退出码 3），不会被静默忽略。

`delete files` 与 `clean` 的区别：没写 `--targets` 时，`delete files` 删所有匹配项，`clean` 只删默认勾选项。

## 参数

参数写法：`--name value` 或 `--name=value`；`--tool`、`--rule`、`--targets` 可逗号分隔，也可重复写。

### 文件筛选

```bash
--tool <id,id>           # codex, claude, cursor, opencode, deepseek-hermes
--type <text>            # 清理项 id 子串匹配，如 cache, logs, downloads, sandbox, plugins, runtime
--older-than <duration>  # 文件修改时间早于，30d / 7h / 30m / 10s
--newer-than <duration>  # 文件修改时间晚于
--size-gt <size>         # 清理项剩余总大小大于，100MB / 1GB（B/KB/MB/GB/TB，1024 进制）
--size-lt <size>
--safe-only              # 只要 SAFE 级别的清理项
--targets <id,id>        # 只 delete files / clean 可用；id 必须属于所选工具，写错直接报错
```

带时间筛选时只删匹配的文件，目录和链接保留；`--size-*` 判断的是筛完后剩下的大小。

### 会话筛选

```bash
--tool <id,id>
--older-than <duration>  # 会话更新时间早于
--newer-than <duration>
--size-gt <size> / --size-lt <size>
--keep-days <n>          # 排除 N 天内更新过的会话
--keep-recent <n>        # 见下方说明
--rules                  # 用已保存的自动清理策略匹配（只算已启用规则）
--rules-file <path>      # 用策略 JSON 文件匹配（'-' 读标准输入），不能和 --rules 同用
--rule <id,id>           # 只用指定规则，并强制视为启用（方便试跑未开启的新规则）
```

> **`--keep-recent` 是在其他筛选之后才生效的。** 它从*已匹配*的结果里去掉最新的 N 条，不是全局保留最近 N 条。
> 例：`--older-than 30d --keep-recent 10` 保留的是「30 天前那批里最新的 10 条」。匹配数不足 N 时什么都不删。

策略匹配最后执行，和其他筛选取交集。

## 使用示例

### AI 调用模式

```bash
# 1. 查询匹配什么
cxclear find chats --older-than 30d

# 2. 预览删除计划
cxclear delete chats --older-than 30d --keep-recent 10

# 3. 执行删除
cxclear delete chats --older-than 30d --keep-recent 10 --yes
```

### 文件清理

```bash
# 清理默认勾选里的安全项
cxclear clean --safe-only --yes

# 清理大缓存
cxclear delete files --type cache --size-gt 100MB --yes

# 只删指定清理项
cxclear delete files --tool codex --targets codex.logs-db --yes
```

### 策略

```bash
cxclear rules get
cxclear rules validate --file rules.json
cxclear find chats --rules-file rules.json --rule r1   # 试跑某条规则会删哪些
```

CLI 没有写入策略的命令，策略只能在 GUI 设置页编辑。

## JSON 输出

### 查找

```json
{
  "ok": true,
  "command": "find chats",
  "total": 1,
  "bytes": 2345678,
  "sessions": [
    {
      "id": "cursor:abc123",
      "tool": "cursor",
      "session_id": "abc123",
      "title": "重构登录模块",
      "project": "D:/project/demo",
      "updated_millis": 1234567890000,
      "bytes": 2345678,
      "bytes_label": "2.24 MB"
    }
  ]
}
```

`find files` 的列表字段是 `targets`，每项含 `tool`、`target_id`、`label`、`risk`、`default_selected`、`bytes`、`files`、`bytes_label`。
用了 `--rules` / `--rules-file` / `--rule` 时会多一个 `rules` 字段，列出来源、规则数和告警（如规则被禁用、条件不完整）。

### 删除预览（未带 `--yes`，或带了 `--preview`）

```json
{ "ok": true, "command": "delete files", "preview": true, "matched": 2, "bytes": 1048576, "targets": [ ... ] }
```

会话删除的列表字段是 `sessions`。没匹配到时 `matched` 为 0、列表为空数组。

### 删除执行（带 `--yes`）

```json
{ "ok": true, "command": "delete files", "preview": false, "freed_bytes": 1048576,
  "targets": [ { "target_id": "...", "label": "...", "freed_bytes": 1048576, "error": null } ],
  "errors": [] }
```

```json
{ "ok": true, "command": "delete chats", "preview": false, "deleted": 3, "freed_bytes": 2345678,
  "blocked_tools": [], "errors": [] }
```

没匹配到任何东西时同样输出 `preview: false`，释放量为 0。

## 错误反馈

| 情况 | 退出码 | stdout |
|------|--------|--------|
| 参数错误：未知命令 / 未知参数 / 命令不支持该参数 / 时长或大小格式不对 / 未知工具 / 未知 `--targets` / 未知 `--rule` / 策略文件不存在或不合法 | `3` | `{"ok": false, "error": "<原因>"}`，同一句原因也会打到 stderr |
| `rules validate` 校验不通过 | `3` | `{"ok": false, "errors": ["...", ...]}` |
| 删文件时目标工具正在运行（一个都不删） | `2` | `{"ok": false, "command": ..., "preview": false, "blocked_tools": [...], "error": "<工具> is still running"}` |
| 删会话时目标工具正在运行（一个都不删） | `2` | 执行结果格式，`ok: false`、`blocked_tools` 非空 |
| 删除部分失败 | `1` | 执行结果格式，`ok: false`，失败原因在 `errors` 里（文件删除另在每个 `targets[].error`） |
| 其他未预期异常 | `1` | `{"ok": false, "error": "<异常信息>"}` |

常见报错原文示例：

- `unknown option: --nope`
- `delete files does not support --keep-recent`
- `unknown tool: foo`
- `unknown target: nope`
- `invalid duration: 3x (examples: 30d, 7h, 30m)`
- `--rules and --rules-file cannot be combined`

完整命令定义：`cxclear schema`  
设计理念详解：`doc/CLI设计理念.md`
