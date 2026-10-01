# CLI 文档

命令行工具为 AI 和脚本提供 JSON 接口，输出走 `stdout`，消息走 `stderr`。

## 安装

Windows 下一行命令安装到 `%LOCALAPPDATA%\cxclear`，并写入用户 PATH：

```powershell
irm https://raw.githubusercontent.com/moumoum0/cx-clear/main/install.ps1 | iex
```

没有可用的 Java 21 时会询问是否下载 [Eclipse Temurin 21 JRE](https://adoptium.net/)，确认后放到安装目录的 `runtime`，不改已有的 `JAVA_HOME`。拒绝则只装好 CLI，此时 `cxclear` 起不来。

静默安装不问，缺 Java 21 就直接下载：

```powershell
$env:CXCLEAR_SILENT = '1'; irm https://raw.githubusercontent.com/moumoum0/cx-clear/main/install.ps1 | iex
```

## 设计理念

- **查询→统计→删除**：先 `find` 看匹配什么，再 `delete --preview` 确认计划，最后 `--yes` 执行
- **参数继承**：同一筛选条件在 `find` 和 `delete` 通用
- **默认安全**：不带 `--yes` 只预览，不删文件
- **无状态**：每次调用独立，不依赖上次扫描结果

## 核心命令

| 命令 | 说明 |
|------|------|
| `find chats` | 查找对话 |
| `delete chats` | 删除对话 |
| `find files` | 查找可清理文件 |
| `delete files` | 删除文件 |
| `scan` / `clean` | 扫描与清理 |
| `status` / `history` | 查看状态与历史 |
| `rules get` / `validate` | 管理自动清理策略 |
| `schema` / `help` | 查看完整定义 |

## 常用筛选

```bash
--tool <id>              # codex, claude, cursor, opencode, deepseek-hermes
--type <type>            # cache, logs, downloads, sandbox, plugins, runtime
--older-than <duration>  # 30d, 7h, 30m
--newer-than <duration>
--size-gt <size>         # 100MB, 1GB
--size-lt <size>
--keep-recent <n>        # 保留最近 N 条会话
--keep-days <n>          # 保留最近 N 天的会话
```

## 使用示例

### AI 调用模式

```bash
# 1. 查询匹配什么
cxclear find chats --older-than 30d

# 2. 预览删除计划
cxclear delete chats --older-than 30d --keep-recent 10 --preview

# 3. 执行删除
cxclear delete chats --older-than 30d --keep-recent 10 --yes
```

### 自动化脚本

```bash
# 定期清理安全项
cxclear clean --safe-only --yes

# 清理大缓存
cxclear delete files --type cache --size-gt 100MB --yes

# 查看占用
cxclear status
```

### 策略管理

```bash
# 读取策略
cxclear rules get

# 校验策略 JSON
cxclear rules validate < rules.json
```

## JSON 输出

```json
{
  "ok": true,
  "command": "find chats",
  "total": 5,
  "bytes": 12345678,
  "sessions": [
    {
      "id": "cursor:abc123",
      "tool": "cursor",
      "title": "重构登录模块",
      "updated_millis": 1234567890000,
      "bytes": 2345678,
      "bytes_label": "2.24 MB"
    }
  ]
}
```

## 退出码

- `0` 成功
- `1` 执行失败
- `2` 目标工具仍在运行
- `3` 参数或规则校验失败

完整命令定义：`cxclear schema`  
设计理念详解：`doc/CLI设计理念.md`
