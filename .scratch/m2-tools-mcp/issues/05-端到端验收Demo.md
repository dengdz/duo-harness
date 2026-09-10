# 05 — 端到端验收 Demo（filesystem server）

## What to build

M2 验收件（duo-acceptance 里程碑级）：demo.yml 扩展 + DemoMain 扩展 + 验收对照表。demo.yml 新增 MCP 配置行（filesystem server 指向临时目录）与审批策略配置；DemoMain 新增段落演示——MCP 工具自动出现在清单、经三段管线调用 `read_file` 读真实文件、审批策略拒绝演示、guard 治理演示、拔掉 MCP 配置行后工具消失。输出延续 plugin/status + demo/log 叙述风格，产出 M2 验收对照表（含预期日志原文快照，duo-acceptance 里程碑级范本）。完成的判据：一条命令（mvn -pl duo-harness-example -am package exec:java）跑通全部演示，用户按对照表逐行核对通过。

## Blocked by

02, 03, 04

## Status

ready-for-agent

## Checklist

- [ ] demo.yml 扩展：MCP 配置行（filesystem server 指向临时目录）+ 审批策略配置
- [ ] DemoMain 扩展：MCP 工具清单叙述 / read_file 调用 / 审批拒绝演示 / guard 治理演示
- [ ] M2 验收对照表（acceptance.md）：逐工单验证方式 + 预期日志原文快照（duo-acceptance 里程碑级）
- [ ] 文档同步：docs/01-入门 补 MCP 段落；limitations.md 更新（强化件条目消除、MCP 条目消除）
- [ ] 用户按对照表完成手动验收（done 的定义）
