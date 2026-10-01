# 08: 指南新增——hooks + MCP 接入

## What to build

两篇新指南：①hooks（用户级 hooks.json 配置、PreToolUse/PostToolUse 两事件、载荷字段与退出码语义）；②MCP 接入（config `mcp` 段写法、启动连接与重连行为、状态面确认工具到位——配置与使用层；机制深入链接《MCP 深入》，不重复）。与《MCP 深入》分工：指南讲配置使用，高级讲机制。

## Blocked by

01（大纲确认）。

## Status

done（2026-10-01 用户验收通过）

## Checklist

- [x] hooks 篇成稿：两个挂点（PreToolUse 阻断/放行/转审批、PostToolUse 结果改写为错误）、hooks.json 位置与插件行 opt-in、matcher 组形态样例 + Codex 扁平形态、字段速查表（matcher 多选正则/timeout 缺省 600）、stdin 载荷字段表（六字段含 presenter_id、DUO_HOME env）、裁定两法（exit 2 / stdout JSON deny|allow|ask）、fail-open 语义与「只收不放」分工
- [x] MCP 接入篇成稿：装配行完整样例（字段与缺省值逐一锚 McpConnectionOptions#20-74：serverName 约束/failOnStartupError 缺省 false/reconnectEnabled 缺省 true/reconnect 嵌套 500ms-30s-10 次/requestTimeoutMs 20s）、启动确认三处（控制台/状态面连接器区 BACKOFF-GAVE_UP/问模型 mcp__ 命名）、排障六行表
- [x] 与《MCP 深入》互链不重复（机制段一句话 + 链接）；**顺带核实**：组装指南样例的嵌套 reconnect 形态合法（from() 归一化认嵌套，探索报告的扁平字段是 record 内部形态——虚惊一场无需修）
- [x] 内链自查 + vitepress build 通过（2026-10-01，1.33s）；sidebar 两篇入册；口吻自查零命中
- [x] 用户验收通过（2026-10-01）；CHANGELOG 0.27.0 段同 diff 记账

## Comments

- 2026-10-01 产出：`docs/02-指南/hooks.md`（约 70 行）、`docs/02-指南/MCP接入.md`（约 60 行）。**指南批 8 篇全部完成**。MCP 样例缺省值逐项锚定源码（含一处对账核实：嵌套 reconnect 形态合法）。
