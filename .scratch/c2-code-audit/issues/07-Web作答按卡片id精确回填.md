# 07: Web 作答按卡片 id 精确回填

## What to build
前端提问卡与计划卡的四处作答发点补齐卡片 id，后端作答完成逻辑按 id 精确匹配悬空请求——多卡排队与多标签并发场景下每次作答命中对应卡片，不再"答给最旧的"。后端旧无 id 形态保留一个版本窗口（兼容缓存页），记档弃用时点。

证据锚点：审计报告 P2-A 族第 2 条（M24-02 按 id 回填只升级了审批卡，提问/计划卡仍走无 id 旧形态）。

验收标准（用户可感）：同时挂起提问卡与计划卡时作答各命中其卡；跨标签并发不串卡。

## Status
in-progress（实现完成待手动验收，2026-09-28）

## Checklist
- [x] 后端按 id 精确回填（completeById + decision=answer 分支 M24-02 已有——本票接线前端即可；WebAnswerer.complete javadoc 更新弃用时点 0.25.0）
- [x] 前端四处发点补齐 id（answer-value / answer-free / plan-approve / plan-reject → {id, decision:'answer', answers}；无 id 形态残留 grep 清零）
- [x] 队列多项并发回填用例（concurrentPendingCompletedByIdNotByOrder：乱序作答不串卡）
- [x] 旧无 id 形态兼容窗口记档（后端两分支保留 + javadoc 注明 0.25.0 移除）
- [x] CHANGELOG 记账（用户可见：并发作答不再串卡）

## Comments

- 2026-09-28 实现：前端为协议字段变更（payload 加 id/decision），视觉面零改动（卡片渲染与 resolveCard 不变）；作答路由行为建议随 0.24.0 验收件实测（提问卡+计划卡并发作答各命中其卡）。id 全链路核对：InteractionRequest 构造器自动生成 UUID → 审计桥 approvalRequested 借 toolCallId 通道 → 前端 event.toolCallId → cardId。web 98 用例全绿。

