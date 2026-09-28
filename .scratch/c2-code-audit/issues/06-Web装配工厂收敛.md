# 06: Web 装配工厂收敛

## What to build
Web 呈现位的初始会话装配与换绑会话装配（onSessionChanged 回调）走同一装配工厂：agent 规格与能力集构建、fileRefs 失效监听器附着各收敛为单点。新增能力项时初始与换绑两条路径不再可能漏改分叉。

证据锚点：审计报告 P2-B 族第 7 条（AgentSpec/AgentCapabilities 逐参双份 + 监听器双份）。

验收标准（用户可感）：换绑后的会话与初始会话在能力面上完全一致（同一工厂产出）；新增能力只需改一处。

## Status
ready-for-agent

## Checklist
- [ ] 装配工厂提取（agent 规格 + 能力集构建单点）
- [ ] fileRefs 监听器附着收敛单点
- [ ] 两处调用方接线工厂
- [ ] 两路径装配一致性断言 + 换绑既有用例全绿
- [ ] CHANGELOG 记账
