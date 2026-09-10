# 02 — 远端工具同步进 tools 服务

## What to build

连接之上长出能力：把 MCP 服务器暴露的远端工具自动注册进工具域。`tools/list` 全量拉取 → 命名清洗与冲突校验 → `mcp__<server>__<tool>` 命名注册进 ToolsService（注册即连接作用域 effect）→ 调用映射（`execute` 转发远端 `tools/call`，isError 与传输失败收敛为 error 结果，text content 透传）。工具同步两阶段原子换新：同步失败保留旧一代继续服务；`tools/list_changed` 通知触发自动重同步。完成的判据：filesystem server 的全部工具出现在工具清单中、能经三段管线执行读到真实文件、杀掉 server 后工具保留（旧代继续）、重连后工具自动恢复（接缝 C）。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] 工具同步两阶段：全量拉取校验（重名/冲突失败）→ 原子换新（旧代注销、新代注册）
- [ ] 命名：`mcp__<server>__<tool>` + 非法字符清洗（清洗冲突即失败点名）
- [ ] 调用映射：execute → 远端 callTool（raw 名），isError/传输失败 → error 结果，text content 透传
- [ ] `tools/list_changed` → 自动重同步（syncChain 串行防交错）
- [ ] outputSchema 的契约校验接线**不在本票**（宽松透传，04 接线）
- [ ] 接缝 C 测试：工具出现/执行/变更同步/断连保留旧代/恢复
