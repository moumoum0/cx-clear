# CX Clear CLI

 Codex、Claude Code、Cursor、Open Code、DeepSeek Hermes专业清理工具，支持使用强大的条件筛选以及配置自己的清理策略

## 特色功能

- **可扩展性强**：扁平化设计，单个应用的所有规则都放在一个文件夹内，可扩展性强
- **自动策略**：直接让 AI 创建自动清理策略
- **命令行**：专为 AI 清理使用的命令行，提供多种筛选条件以及内置的规则
## 安装

```powershell
npm install -g cxclear
cxclear --version
```



## 命令

| 命令 | 说明 |
|------|------|
| `find chats` | 查找对话 |
| `delete chats` | 删除对话 |
| `find files` | 查找可清理文件 |
| `delete files` | 删除文件 |
| `scan` / `clean` | 按内置名单扫描和清理 |
| `status` / `history` | 查看占用、对话数量和清理历史 |
| `rules get` / `validate` | 读取和校验自动清理策略 |
| `schema` / `help` | 查看完整定义 |
| `version` / `--version` / `-v` | 查看版本号 |

所有命令都输出 JSON。

## 筛选

```text
--tool <id>              codex, claude, cursor, opencode, deepseek-hermes
--type <type>            cache, logs, downloads, sandbox, plugins, runtime
--older-than <duration>  30d, 7h, 30m
--newer-than <duration>
--size-gt <size>         100MB, 1GB
--size-lt <size>
--keep-recent <n>        保留最近 N 条会话
--keep-days <n>          保留最近 N 天的会话
--preview                只预览
--yes                    执行删除
--safe-only              只包含安全项
```

`find` 和 `delete` 用同一套筛选。`--preview` 不会删除。

## 示例

```powershell
cxclear find chats --older-than 30d
cxclear delete chats --older-than 30d --keep-recent 10 --preview
cxclear delete chats --older-than 30d --keep-recent 10 --yes

cxclear find files --type cache
cxclear delete files --type cache --size-gt 100MB --yes
cxclear scan --safe-only
cxclear clean --safe-only --yes

cxclear status
cxclear history --limit 20
cxclear rules get
cxclear rules validate
```

`rules validate` 从 `--file` 或标准输入读取策略 JSON。

## 退出码

- `0` 成功
- `1` 执行失败
- `2` 目标工具仍在运行
- `3` 参数或规则校验失败

完整定义：`cxclear schema`。文档：https://github.com/moumoum0/cx-clear/blob/main/wiki/CLI-Usage.md

## 许可

GPL-3.0-only。见 LICENSE 和 NOTICE.md。
