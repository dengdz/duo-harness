# 05: SSE 增量回放

## What to build

刷新页面或断线重连只补收缺失的事件，不再重放全部历史（80K token 会话事件上千的现状下刷新应秒级完成）；首次连接保持全量快照 + 边界帧不变，游标对不上号时全量重发兜底——宁可重放不可丢事件（ADR-0010）。

## Blocked by

04（同在 WebFace 的 SSE 域，串行避免同文件冲突）

## Status
ready-for-agent

## Checklist
- [ ] 会话事件帧携带 id（取事件在日志中的下标）；replay/done、run/error 等非会话帧不带数字 id
- [ ] 携 Last-Event-ID 的连接只收到其后的事件（HTTP 层帧序列测试）
- [ ] 游标越界（超日志范围）退化为全量快照兜底（测试）
- [ ] 前端 EventSource 重连后增量帧正确渲染（浏览器冒烟）
