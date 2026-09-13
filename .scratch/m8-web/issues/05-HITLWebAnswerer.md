# 05: HITL Web answerer——待答卡片推送与断连 fail-closed

**What to build:** M6 交互 seam 的 Web 呈现位：`WebAnswerer` 实现 Answerer 接口注册进交互服务——审批请求渲染为两按钮卡片（y/n）、提问渲染为选项+自由输入卡片、计划呈交渲染为批准/打回卡片；待答请求经 SSE 推送到页面，用户点选/输入后 `POST /api/answer` 携带答案完成等待中的请求（CompletableFuture 阻塞，虚拟线程友好）；**SSE 断连 → 全部悬空请求立即 fail-closed**（人不在环 = 不批准，ADR-0008 语义延伸）。

**Blocked by:** 02, 03（骨架承载 + 卡片样式蓝图冻结）

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] `WebAnswerer implements Answerer`：answer() 创建 Future + 待答态，POST /api/answer 完成并返回 InteractionAnswer（CompletableFuture 阻塞在虚拟线程上 + 兜底超时 fail-closed）
- [x] 待答请求经 SSE 推送（approval/requested 事件 → 页面审批两按钮卡片；提问/计划卡片同管线，工单 06 收口全形态）
- [x] `POST /api/answer`：approved 布尔（审批）/ values 数组（提问）→ 组装 InteractionAnswer 解除阻塞；无待答幂等 false
- [x] **SSE 断连 → 悬空请求立即 fail-closed**（双路径：events 端点 IOException + pushEvent 全客户端断开；ADR-0008 语义延伸）
- [x] 测试：WebAnswererTest 5 例（审批 POST 完成署名 web / 提问值回传 / 断连 fail-closed / 超时兜底 / 幂等拒绝）
- [x] 文档同步：CHANGELOG 未发布段记 HITL Web answerer

## 实现记录（2026-09-13）

- **ADR-0008 验证确认**：WebAnswerer 只实现 Answerer 接口 + 注册进交互 seam——审批策略、guard、六段管线零改动（机制核一行未动，"换呈现位不动机制"成立）
- 断连 fail-closed 双路径：/api/events 写失败 + pushEvent 检测全部客户端断开 → webAnswerer.failClosedAll()
- 兜底超时（默认 10 分钟）：防"页面在但人不看"的极端悬空；超时按 fail-closed
- 过程修正：WebFace 骨架测试传 null answerer（start 对 null 宽容）；WebPlugin inject 补 answers

## Comments

spec：[../spec.md](../spec.md)。断连 fail-closed 是 ADR-0008 安全语义在 Web 呈现位的延伸，也是"换呈现位"验证的一部分；fail-closed 后计划模式仍激活（模型可继续完善或用户重开页面再答）。
