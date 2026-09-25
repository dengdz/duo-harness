# 05: 压缩熔断计数 + summary 预算隔离

## What to build
microcompact 的两张安全网：① 压缩熔断计数——summary 压缩连续失败达阈值时熔断（停止继续尝试压缩，会话继续可用），防"压缩失败→重试→再失败"循环卡死；② summary 预算隔离——microcompact 与既有 summary 压缩并存互不干扰（各管各的预算，用户故事 14/15）。交付后的可感行为：长会话在任何压缩异常下都能继续聊。

## Blocked by
04

## Status
ready-for-agent

## Checklist
- [ ] 压缩熔断计数（连续失败阈值 + 熔断后行为：会话继续、状态可见）
- [ ] microcompact 与 summary 压缩并存互不干扰（预算隔离用例）
- [ ] tdd 红绿循环（熔断触发 + 并存用例，seam 先与用户确认）
