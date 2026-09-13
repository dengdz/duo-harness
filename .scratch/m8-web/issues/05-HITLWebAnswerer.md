# 05: HITL Web answerer——待答卡片推送与断连 fail-closed

**What to build:** M6 交互 seam 的 Web 呈现位：`WebAnswerer` 实现 Answerer 接口注册进交互服务——审批请求渲染为两按钮卡片（y/n）、提问渲染为选项+自由输入卡片、计划呈交渲染为批准/打回卡片；待答请求经 SSE 推送到页面，用户点选/输入后 `POST /api/answer` 携带答案完成等待中的请求（CompletableFuture 阻塞，虚拟线程友好）；**SSE 断连 → 全部悬空请求立即 fail-closed**（人不在环 = 不批准，ADR-0008 语义延伸）。

**Blocked by:** 02, 03（骨架承载 + 卡片样式蓝图冻结）

**Status:** ready-for-agent

## Checklist

- [ ] `WebAnswerer implements Answerer`：answer() 创建 Future + 待答态，POST /api/answer 完成并返回 InteractionAnswer
- [ ] 待答请求经 SSE 推送（审批/提问/计划复核卡片按 kind 区分渲染，样式按工单 03 蓝图）
- [ ] `POST /api/answer`：选项序号或自由文本 → 组装 InteractionAnswer 解除阻塞；重复回答/无待答请求幂等拒绝
- [ ] **SSE 断连 → 悬空请求立即 fail-closed**（complete 为拒绝形态，agent 线程解除阻塞）
- [ ] 测试（交互 seam 装配，InteractiveApprovalTest 先例）：POST 回答解除阻塞 / 断连 fail-closed / 幂等拒绝
- [ ] 文档同步：CHANGELOG 未发布段记 HITL Web answerer；ADR-0008 的"呈现位实现不触碰机制核"在实现记录中显式确认

## Comments

spec：[../spec.md](../spec.md)。断连 fail-closed 是 ADR-0008 安全语义在 Web 呈现位的延伸，也是"换呈现位"验证的一部分；fail-closed 后计划模式仍激活（模型可继续完善或用户重开页面再答）。
