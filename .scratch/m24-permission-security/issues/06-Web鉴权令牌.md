# 06: Web 鉴权令牌

## What to build

Web 启动生成随机 token（安全随机数）、控制台打印带 token 的 URL（`http://127.0.0.1:<port>/?token=<t>`）；浏览器首载后存 localStorage，后续 HTTP 头 + SSE query 携带；校验失败一律 403（fail-closed，覆盖静态资源与全部 /api 端点）；yml `web.auth: none` 显式关闭，关闭时启动横幅警示「鉴权已关闭」；token 进程生命周期一次一发、无过期轮换。

决策依据：ADR-0026 决策五；backlog「Web 鉴权令牌 + bind」M24 正式范围（ADR-0024）；术语表新增「鉴权令牌」词条。

## Blocked by

无（可立即开工）

## Status
ready-for-agent

## Checklist
- [ ] token 生成与带 token URL 打印
- [ ] 全端点 fail-closed 403 / 带 token 放行（HTTP 头 + SSE query 双通道）
- [ ] 前端 localStorage 持有与自动携带
- [ ] yml 显式关闭开关 + 启动横幅警示
- [ ] 测试（先例 WebFaceTest / BootTest）
- [ ] 工单级验收件：无 token 403 / 带 token 放行 / 关闭横幅演示，用户手动确认
- [ ] CHANGELOG 记账（0.19.0 段）
