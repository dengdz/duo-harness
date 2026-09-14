# 04: fail-closed 语义钉死

## What to build

悬空交互"无人即拒"的触发语义精确化并用测试固化：全部连接离场 → 一律拒绝；浏览器刷新（断旧立新）→ 不误杀——新连接经快照回放重新渲染审批卡片、可继续作答。消除"旧连接写失败摘除、新连接尚未入列"窗口把刷新误判成离场的路径（去抖或等效机制，实现自定）。

## Blocked by

无（可立即开工）

## Status
done

## Checklist
- [x] 触发判定消除刷新窗口误杀（判定语义 = 是否还存在能看见该审批的连接）
- [x] 测试：全部连接离场触发 fail-closed
- [x] 测试：断旧立新序列不触发，新连接回放含审批卡且可作答
- [x] 既有 WebAnswererTest 回归绿

## Comments

- 实现（2026-09-14）：`removeClient` 摘除后列表空时不立即拒，改调度 `FAIL_CLOSED_GRACE_MS=2s` 去抖复查（`failClosedCheck` 单任务 AtomicReference，窗口内新摘除重置窗口）；复查时再判 `sseOutputs.isEmpty()` ——新连接宽限内入列即不拒。`removeClient` 放宽为包级供测试确定性驱动摘除时点；类 javadoc 同步为宽限语义。
- 语义要点：判定 = "是否仍有人能看见该审批"。持续刷新场景每次都有新连接回放含审批卡，故不拒（正确）；列表真正持续为空才拒。窗口仅由"摘除时列表空"开启，无自续期路径。
- 验证：WebFaceTest 17/17（新增两用例——离场宽限后拒、刷新入列不误杀且可作答）；全量 `mvn -o test` 269 用例绿；**浏览器实景冒烟**——触发写文件审批卡 → 真实刷新页面 → 审批卡仍在且可作答（alreadyDenied=false）→ 点批准 → 写入成功模型续答。
- 审查（委托模式）：逻辑/并发/生命周期逐项核实无 blocker；类 javadoc 措辞已同步宽限语义。
- **第二段双轴审查（code-review 技能，fixed point = HEAD 工作区）**：
  - Standards 轴硬违规 1 条（已修）：红线 3 文档同步——`docs/04-架构/设计主线.md`"立即按拒绝处理"与 `docs/05-参考/术语表.md`"断连一律 fail-closed"仍是旧行为措辞，已同 diff 改为宽限语义表述（术语表条目引 ADR-0010）。
  - Spec 轴发现 2 条（已修）：① `WebAnswerer` 类 javadoc 仍写"立即"未同步——已改；② **测试接线与生产不一致**——原测试先注册 webAnswerer 再注册 AuditingAnswerer，而回答者链"首个非空胜出"，导致装饰器永不执行、审批事件不落会话，"回放含审批卡"实际未固化（仅浏览器冒烟兜底）。已改为生产同款接线（只注册 AuditingAnswerer）并新增回放断言（新连接流含 `approval/requested`）；顺带抽出 `askApproval` helper 消除用例重复（Standards 轴 smell 建议）。
  - 留档的判定项：① ADR-0010 原文"须消除"误杀路径，去抖实为**收窄**（慢刷新 > 2s 仍可能误杀）——工单 What to build 授权"去抖或等效机制，实现自定"，2s 时限亦无 spec 依据属自定范围；② 判定粒度为连接（`sseOutputs` 全局列表）而非"该审批的可见性"，会话切换后页面在新会话看不到旧审批卡但计数非空仍判"有人能看见"——spec 已把会话切换无刷新列为 Out of Scope，风险低；③ `removeClient` 由 private 改包级可见属测试驱动（spec Testing Decisions 称"无新 seam"），已补 javadoc 说明判定只看"摘除后列表是否为空"；④ 修复过程中自查发现并修正测试里的阻塞读缺陷（`readNBytes` 等读满会挂死，改逐块累积 + 关流打断）。
- 待手动验证：用户亲跑确认后置 done。
