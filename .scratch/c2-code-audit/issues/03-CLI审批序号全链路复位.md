# 03: CLI 审批序号全链路复位（P1 止血）

## What to build
真实 CLI 会话中每轮对话的审批排队提示（"（本轮第 i 项审批）"）从零重计——turn 边界触发归零调用补回 turn 线程模型，第二轮对话首项审批回到零噪声形态。归零链路走 REPL 全链路（不再依赖测试直调），只写不读的回答者字段消除。

证据锚点：审计报告 P1-3（turn 重构丢调用、测试直调掩盖）。

验收标准（用户可感）：连续两轮对话、每轮含审批，第二轮首项审批提示不带跨轮累计的序号。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] turn 边界补回归零调用（runOneTurn 首行——每轮对话边界，排队消息轮各自独立计数）
- [x] REPL 全链路回归用例（approvalCounterResetsPerTurnThroughRepl：真实事件循环 + 应答闸门驱动两轮对话各一次审批，断言第二轮首项零噪声）
- [x] 既有直调单元用例保留为单元层（ConsoleAnswererTest 不动）
- [x] CHANGELOG 记账

## Comments

- 2026-09-28 实现：`runOneTurn` 首行补回 `consoleAnswerer.beginTurn()`。全链路用例稳定化两招（首版踩中 AnswerGate 已知微窗——审批呈现打印到 pending 置位之间投递的 y 被当插队注入收件箱）：①测试 Fixture 的 `InputLine` 新增 `pacedSettled`（标记命中后静置 150ms 再投递，压过毫秒级窗口）；②两轮对话用不同审批工具名（guarded_write / guarded_save）制造轮 2 独有投递标记。同轮计数到 2 的正常形态由既有直调用例覆盖不重复。稳定度 5/5，CLI 全量 41 用例全绿。

