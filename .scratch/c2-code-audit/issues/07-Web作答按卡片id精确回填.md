# 07: Web 作答按卡片 id 精确回填

## What to build
前端提问卡与计划卡的四处作答发点补齐卡片 id，后端作答完成逻辑按 id 精确匹配悬空请求——多卡排队与多标签并发场景下每次作答命中对应卡片，不再"答给最旧的"。后端旧无 id 形态保留一个版本窗口（兼容缓存页），记档弃用时点。

证据锚点：审计报告 P2-A 族第 2 条（M24-02 按 id 回填只升级了审批卡，提问/计划卡仍走无 id 旧形态）。

验收标准（用户可感）：同时挂起提问卡与计划卡时作答各命中其卡；跨标签并发不串卡。

## Status
in-progress（实现 + 端到端实测通过，2026-09-29；待用户最终验收置 done）

## Checklist
- [x] 后端按 id 精确回填（completeById M24-02 已有；`{id, answers}` 载荷 id 分支 decision 缺省 answer——不触 M16 互斥协议）
- [x] **提问卡实时渲染事件源（BUG-20260929-01 修复）**：SessionEvent 新增 `question/requested`；AuditingAnswerer 对 KIND_QUESTION ask 前落事件（携请求 id + 问题/选项 JSON）
- [x] 前端四处发点 `{id, answers}` 形态（answer-value / answer-free / plan-approve / plan-reject；计划卡 cardId 来自 approval/requested 的请求 id）；questionCard 挂 cardId + 在途去重 + 实时流 tool/call 门卫（replaying 标志，消重复卡）
- [x] 队列多项并发回填用例（端点级：completeById 直调 + HTTP `{id, answers}` 两形态，乱序作答不串卡）
- [x] CHANGELOG 记账（用户可见：弹卡实时渲染 + 并发作答不串卡）

## Comments

- 2026-09-28 首版：id 形态直改前端——后经两轮验收实测推翻（见下），教训为「测试直调绕过前端真实 payload 形态」。
- 2026-09-29 验收回路全程（用户实测 + 隔离实例浏览器实证）：①首版 id 载荷带 decision 违反 M16 互斥协议被 400 丢弃；②更深一层发现 **BUG-20260929-01**：提问卡挂起期间根本没有渲染事件源（tool/call 成对提交要到完成后才落盘），卡片永不出现——作答通路再对也无处可点。最终修复 = question/requested 前置事件（镜像审批卡）+ `{id, answers}` 载荷 + 实时流 tool/call 门卫 + 端点级回归锁（无 id 兼容形态与 id 精确形态各一）。**隔离实例内置浏览器端到端实测：弹卡 3 秒实时渲染 → 页面点击 → 模型即时回复，无重复卡。**全仓 verify 绿。


