# 03 — AgentRepl demo 与验收对照表

## What to build

`AgentReplMain`（example）：Boot 配置（tools 插件 + 审批 always-deny + MCP files 连接）装配 `ToolsService` 与会话目录，注入 ChatAgent——两大标志性场景端到端：

1. **自主调工具**：用户"读一下 notes.txt" → LLM 调用 `mcp__files__read_file` → 真实文件内容回填 → LLM 总结回答
2. **治理可见**：LLM 想写文件 → 审批拒绝 → LLM 向用户解释"写被策略拒绝了"

REPL 交互（`你> `/`AI> `、过程叙述：调工具/工具结果/审批拒绝）、`/exit` 退出；验收对照表（本工单 Comments）。

## Blocked by

01, 02

## Status

ready-for-agent

## Checklist

- [ ] AgentReplMain：Boot 配置装配（tools + approval always-deny + MCP files）+ ChatAgent 注入 + REPL 循环（过程叙述：调工具/工具结果）
- [ ] 标志性场景：读文件自主调用成功 / 写文件被审批拒绝且 LLM 自行解释
- [ ] 冒烟测试：mock LLM（tool_calls 脚本）+ 真 ToolsService（测试工具）
- [ ] 验收对照表（本工单 Comments）：含预期日志原文段
- [ ] 用户手动验收（done 的定义）
