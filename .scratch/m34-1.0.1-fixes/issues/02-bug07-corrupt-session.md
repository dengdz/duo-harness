# 02: BUG-07 会话坏行三症状——parse 容错 + 锁释放 + 导出明示

## What to build

数据完整性档：会话 JSONL 含坏行时的三个症状一并修（BUG-20261002-07 档案）。①**假设验证**——档案假设 Session.parse 全文逐行遇坏即抛（Session.java:1187 栈已锚）+ load 异常路径锁未释放 + 导出投影复用同一 parse；逐一实钉。②**修复**——parse 跳过坏行并计数（回放显「N 条坏行已跳过」）；load 异常路径锁必释放（try/finally 核对）；导出对坏行计数明示而非静默归零。③**回归锁**——坏行会话可打开可回放、切换后无 409 占用残留、导出事件数=好事件数（30000 行 spill 回归锁同族形态）。spec 见 [spec.md](../spec.md)。

## Blocked by

None (can start immediately)——与 01 无代码交叠，可并行。

## Status

done（2026-10-02 修复 + 回归锁三用例绿 + 全库测试绿；发版归工单 06）

## Checklist

- [x] 三处根因实钉回写 BUG-20261002-07 档案（parse 包装 PluginException 抛出 / 释放 catch 只捕 IOException / 导出经 heldSession 渲染泄漏实例——症状③机制与档案假设「复用同一 parse」不同源，已修正记档）
- [x] 修复落地：坏行跳过+计数（skippedCorruptLines 字段+访问器）、锁释放兜 RuntimeException、导出明示行 + 分页端点字段
- [x] 回归锁入库：CorruptSessionToleranceTest 三用例（跳过计数+连续性 / 结构损坏 fail-loud 且锁释放 / 导出明示）+ SessionTest 旧锁改写注明语义变更
- [x] 档案 Status 流转 + 防复发节填写（「释放 catch 异常类型面」入 review 关注）
- [x] CHANGELOG 记账
- [x] 提交前核对：调试残留 grep 零命中（exit 1）+ session/web 模块全绿 + 全库测试（见 Comments）

## Comments

- 2026-10-02 执行记录：根因实钉修正一处——症状③「导出静默零事件」的机制是 heldSession() 渲染**泄漏的半初始化实例**（parse 抛出时快照未建），非档案假设的「导出复用同一 parse」；异常包装链为 readTree → JsonParseException（IOException 子类）→ parse 包装 PluginException → 穿透 load 只捕 IOException 的释放 catch。
- 语义变更一处：SessionTest.loadRejectsCorruptLine（原锁「坏行 fail-loud」）按 ADR-0036 确认改写为「跳过并计数」断言，改写处原样注明；完整容错形态在 CorruptSessionToleranceTest。
- 前端「N 条坏行已跳过」界面呈现未入 1.0.1（协议面字段 + 导出明示已覆盖交付可信性）——留后续增强。
- 全库测试后台执行完成 exit 0（与工单 01 合并验证轮）；session/web 模块独立复跑亦全绿。
