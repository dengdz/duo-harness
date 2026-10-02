# 02: BUG-07 会话坏行三症状——parse 容错 + 锁释放 + 导出明示

## What to build

数据完整性档：会话 JSONL 含坏行时的三个症状一并修（BUG-20261002-07 档案）。①**假设验证**——档案假设 Session.parse 全文逐行遇坏即抛（Session.java:1187 栈已锚）+ load 异常路径锁未释放 + 导出投影复用同一 parse；逐一实钉。②**修复**——parse 跳过坏行并计数（回放显「N 条坏行已跳过」）；load 异常路径锁必释放（try/finally 核对）；导出对坏行计数明示而非静默归零。③**回归锁**——坏行会话可打开可回放、切换后无 409 占用残留、导出事件数=好事件数（30000 行 spill 回归锁同族形态）。spec 见 [spec.md](../spec.md)。

## Blocked by

None (can start immediately)——与 01 无代码交叠，可并行。

## Status

ready-for-agent

## Checklist

- [ ] 三处根因实钉回写 BUG-20261002-07 档案（parse 语义 / 锁释放缺口 / 导出投影路径）
- [ ] 修复落地：坏行跳过+计数标注、锁必释放、导出明示
- [ ] 回归锁入库：坏行会话加载/切换/导出三断言
- [ ] 档案 Status 流转 + 防复发节填写
- [ ] CHANGELOG 记账
- [ ] 提交前核对：调试残留 grep 零命中 + 测试绿
