# 05: REPL 装配、打磨与验收对照

**What to build:** 把 M6 全部机制装配进 AgentRepl，完成打磨小件与里程碑验收件：console answerer（终端 y/n，one-shot）+ 审计回答者（装饰桥，委托前后写 approval 事件）挂进装配；AgentRepl 补 `/new`；Boot yml 新增 `agent:` 段（maxIterations / retry 参数）；demo 装配升级——写文件挂 interactive 审批、挂载 ask_user、prompt 注册表演示片段。端到端三场景可演示：写文件弹 y/n 且决定入会话、模型中途向用户提问并引用回答、重复调用出现提醒。

**Blocked by:** 01, 02, 03, 04（集成单，全部在场）

**Status:** ready-for-agent

## Checklist

- [ ] console answerer：工具名 + 参数 + 声明来源呈现，`[y=允许本次 / n=拒绝]` 阻塞读一行；EOF/Ctrl+C 按拒绝
- [ ] 审计回答者（装饰桥）：委托前写 `approval/requested`、决定后写 `approval/decided`；可组合、不侵入机制核
- [ ] AgentRepl 补 `/new`（对齐 ChatRepl 行为）
- [ ] Boot yml `agent:` 段：maxIterations 与重试参数接线（缺省保持现值：10 / 3 次）
- [ ] demo 装配：写工具声明 ask + interactive 策略 + ask_user 挂载 + prompts 演示片段（叙述行打印等价 yml）
- [ ] AgentReplMainTest 扩展：三场景冒烟（审批交互 / 模型提问 / 重复提醒）+ `/new`
- [ ] 验收对照表（本工单 Comments）：运行命令 + 预期输出逐段对照
- [ ] 文档同步：运行Demo.md 补 M6 段；CHANGELOG 未发布段收口
- [ ] 用户手动验收（done 的定义）

## Comments

验收对照表骨架（实现时补预期输出原文）：

| 场景 | 输入 | 预期 |
|---|---|---|
| 审批交互 | `帮我写一个 output.txt` | `[待审批]` 呈现工具与参数 → 用户 `y` 放行（文件真实写入）或 `n` 拒绝（模型解释）→ 会话含 approval 事件对 |
| 模型提问 | 需要用户补充信息的任务 | `[提问]` 问题 + 选项 → 用户回答 → 模型继续并引用回答 → 会话含 tool/call + tool/result |
| 重复提醒 | 诱导连续相同调用 | 第 3 次起 `[提醒]` 附加于工具结果 → 模型换方法 |
| 会话管理 | `/new` | 开新会话，旧会话保留可回放 |
