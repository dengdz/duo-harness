# 04: plan-mode

**What to build:** 先计划、人批准、再执行：AgentRepl 新增 `/plan [任务]` 与 `/plan off`；计划模式状态为会话事件 `plan/mode`（entered/exited，续接恢复、审计可回放）；激活时 prompt 注册表挂"计划指导片段"（引导式——不硬禁工具，写操作由 M6 交互审批兜底）；模型完成后经 `exit_plan_mode` 工具（参数 `plan` 计划全文）呈交——走交互 seam 计划复核：批准 = 写 exited 事件并开始执行；打回 = 结果携带用户反馈继续改计划；无回答者 / 未作答 fail-closed（计划不批准，模型可见原因）。

**Blocked by:** None (依赖 M6 已收官的交互 seam 与 prompt 注册表)

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] `plan/mode` 会话事件类型（text = entered/exited）+ 从事件流恢复计划状态（最后一次事件决定）——PlanMode.isActive + AgentRepl 启动恢复
- [x] `/plan [任务]` / `/plan off` REPL 命令（写事件 + 呈现状态叙述）
- [x] 计划指导片段：激活时挂 prompt 注册表，退出即摘除（装配态 PlanHolder：active + guidance 注销器）
- [x] `exit_plan_mode` ToolDefinition：参数 `plan` 必校验；经交互 seam 计划复核（批准精确匹配"批准，开始执行" / 自由文本即打回反馈）；fail-closed 保持计划模式（对齐 DSH）
- [x] 测试（会话往返 seam）：ExitPlanModeToolTest 3 例含事件断言（批准写 exited / 打回不写 / fail-closed 不写）
- [x] 测试（交互 seam 装配）：批准（回调触发）/ 打回（反馈进结果）/ fail-closed 三态
- [x] 文档同步：CHANGELOG 未发布段记 plan-mode；词汇表词条随 M7 收口统一落 CONTEXT.md

## 实现记录（2026-09-13）

- 契约：PlanMode（事件常量 + GUIDANCE 指导文本 + isActive 事件流推导）+ ExitPlanModeTool（answers + Supplier<Session>（/new 换会话后事件仍落新会话）+ approvedCallback（装配侧摘指导片段，Disposable.dispose 的受检异常在装配 lambda 内转换））
- 打回形态：自由文本反馈进工具结果（正常形态非错误）；fail-closed 保持计划模式（对齐 DSH：无评审通道不退模式，模型可继续完善或用户 /plan off）
- /plan 携带任务：进入计划模式后任务按普通输入推进（指导片段已激活，模型自然先计划）

## Comments

spec：[../spec.md](../spec.md)。引导式语义（grill Q4 定案）：不硬禁工具——硬禁需权限预设（M9+）；当前防线是写操作的交互审批。计划复核是 M6 交互 seam 的新消费者（plan-review 意图），ADR-0008 机制呈现分离的第二次兑现。
