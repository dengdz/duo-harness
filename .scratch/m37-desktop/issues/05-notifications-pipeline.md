# 05: 系统通知管线——前端 Web Notification 路径

## What to build

三类事件的到达通道（ADR-0039 Q10 三项全要；spec 落钉为前端 Web Notification API 路径）：渲染层在 turn 完成、审批/提问卡等待人答、执行出错三个时点发 Web Notification（错误附一句摘要）——Electron 将其透传到 macOS 通知中心；渲染层以 `document.visibilityState` 门控（窗口聚焦中不扰）；通知点击 → 聚焦主窗（壳侧承接 click 事件转发）。改动面：duo-harness-web 前端（渲染层已知事件处加通知调用）+ 壳侧点击承接，后端零改动。若 Electron 通知权限/平台行为有出入，壳侧订阅兜底为备选开关（工单期验证后定稿，不回 ADR）。

端到端可验：发长任务切到别的应用 → 审批等待时收到通知 → 点通知主窗聚焦并看到审批卡。

## Blocked by

02

## Status

ready-for-agent

## Checklist

- [ ] 渲染层三类事件通知调用（含 visibilityState 门控、错误摘要文案）
- [ ] 壳侧通知点击 → 聚焦主窗承接
- [ ] Electron 通知平台行为验证（权限/透传）；有出入则启用壳侧兜底开关并记档
- [ ] S3 缝扩展：通知触发门控逻辑单测（聚焦中不扰/后台触发）
- [ ] 真机验证：三类通知触发与点击聚焦（截图留档）
