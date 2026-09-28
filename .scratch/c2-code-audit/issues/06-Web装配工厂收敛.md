# 06: Web 装配工厂收敛

## What to build
Web 呈现位的初始会话装配与换绑会话装配（onSessionChanged 回调）走同一装配工厂：agent 规格与能力集构建、fileRefs 失效监听器附着各收敛为单点。新增能力项时初始与换绑两条路径不再可能漏改分叉。

证据锚点：审计报告 P2-B 族第 7 条（AgentSpec/AgentCapabilities 逐参双份 + 监听器双份）。

验收标准（用户可感）：换绑后的会话与初始会话在能力面上完全一致（同一工厂产出）；新增能力只需改一处。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] 装配工厂提取（apply 内局部函数式 buildAgent：Session → ChatAgent，捕获 apply 局部参数——零新类型）
- [x] fileRefs 监听器附着收敛单点（attachFileRefs 局部函数：初始会话与换绑回调共用）
- [x] 两处调用方接线（初始 agent 构建与 onSessionChanged 回调各一行）
- [x] 两路径装配一致性断言（「同一构建函数」由编译结构保证；换绑行为由 WebFaceTabTest 等 web 97 用例回归锁）
- [x] CHANGELOG 记账

## Comments

- 2026-09-28 实现：与 CliPlugin 的 buildAgent 方法形态不同——Web 侧参数均为 apply 局部（无 CommandChain 先例），用局部函数式收敛（Function/Consumer lambda 捕获 effectively final 局部），零新类型零字段。web 97 用例全绿。

