# 04: plan-mode

**What to build:** 先计划、人批准、再执行：AgentRepl 新增 `/plan [任务]` 与 `/plan off`；计划模式状态为会话事件 `plan/mode`（entered/exited，续接恢复、审计可回放）；激活时 prompt 注册表挂"计划指导片段"（引导式——不硬禁工具，写操作由 M6 交互审批兜底）；模型完成后经 `exit_plan_mode` 工具（参数 `plan` 计划全文）呈交——走交互 seam 计划复核：批准 = 写 exited 事件并开始执行；打回 = 结果携带用户反馈继续改计划；无回答者 / 未作答 fail-closed（计划不批准，模型可见原因）。

**Blocked by:** None (依赖 M6 已收官的交互 seam 与 prompt 注册表)

**Status:** ready-for-agent

## Checklist

- [ ] `plan/mode` 会话事件类型（text = entered/exited）+ 从事件流恢复计划状态（最后一次事件决定）
- [ ] `/plan [任务]` / `/plan off` REPL 命令（写事件 + 呈现状态叙述）
- [ ] 计划指导片段：激活时挂 prompt 注册表（"先探索再设计、不做修改性操作、完成后调用 exit_plan_mode"），退出即摘除
- [ ] `exit_plan_mode` ToolDefinition：参数 `plan` 必校验；经交互 seam 发起计划复核（选项固定"批准，开始执行" / "继续计划，我要给反馈"）；批准写 exited 事件、打回结果携带反馈；fail-closed 三态全覆盖
- [ ] 测试（会话往返 seam）：plan/mode 事件落盘 + 续接恢复
- [ ] 测试（交互 seam 装配，InteractiveApprovalTest 先例）：批准 / 打回（反馈进结果）/ fail-closed
- [ ] 文档同步：CHANGELOG 未发布段记 plan-mode；词汇表新增"计划模式 / exit_plan_mode"词条

## Comments

spec：[../spec.md](../spec.md)。引导式语义（grill Q4 定案）：不硬禁工具——硬禁需权限预设（M9+）；当前防线是写操作的交互审批。计划复核是 M6 交互 seam 的新消费者（plan-review 意图），ADR-0008 机制呈现分离的第二次兑现。
