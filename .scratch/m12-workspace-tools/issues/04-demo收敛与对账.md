# 04: demo 收敛与对账

## What to build

agent-demo.yml 移除 MCP files 沙箱挂载（本机工具取代——消除双写工具选型噪音）+ 全部对账更新：工具目录对账测试（六件入册、`mcp__files__*` 移除）、CliPluginTest 端到端形态断言、运行Demo 验收叙述同步。

## Blocked by

02, 03（本机文件与执行能力就位后才收敛 demo）

## Status
ready-for-agent

## Checklist
- [ ] AgentReplMain 演示装配回调收敛（MCP 挂载段移除；演示提示片段去留裁定并留痕）
- [ ] 工具目录对账测试更新：六件入册、`mcp__files__*` 移除断言
- [ ] CliPluginTest 端到端形态断言更新（MCP 工具不再可用——相关用例改用本机工具或移除）
- [ ] 运行Demo 验收叙述同步（工具名、审批示例形态）
