# 05: REPL 装配、打磨与验收对照

**What to build:** M7 全量装配进 AgentRepl 并完成收官件：技能四根发现 + 清单片段 + skill 工具挂载（含禁用配置演示行）、AGENTS.md 注入接线、`/plan` 与技能直调命令就绪、内置一个演示技能（示例 SKILL.md）、三路触发与计划模式端到端验收对照表、文档同步。验收场景：技能直调 / 模型自触发 skill 工具 / AGENTS.md 生效（模型遵守项目约定）/ /plan 计划复核闭环。

**Blocked by:** 01, 02, 03, 04（集成单，全部在场）

**Status:** ready-for-agent

## Checklist

- [ ] demo 装配：技能四根发现的实际目录接线 + 禁用配置演示行 + skill 工具挂载（叙述行打印等价 yml）
- [ ] 内置演示技能一个（如 `release-notes`：按仓库规范生成发布说明草稿），置于示例可发现目录
- [ ] AGENTS.md 注入接线（演示项目根 AGENTS.md 生效可观察）
- [ ] AgentReplMainTest 扩展：技能直调注入 / skill 工具闭环 / plan 命令状态叙述
- [ ] 验收对照表（本工单 Comments）：运行命令 + 预期输出逐段对照
- [ ] 文档同步：运行Demo.md 补 M7 段；CHANGELOG 未发布段收口；limitations.md 核对（无新增限制则确认）
- [ ] 用户手动验收（done 的定义）

## Comments

### 验收对照表（M7 端到端，实现时补预期输出原文）

**前置**：`~/.duo/config.yml` 配置 llm 段；建议在临时目录跑（避开本仓库 .agents/skills 的工程技能 dogfood 噪声），或在 demo 装配里禁用演示无关技能。

```bash
mvn -pl duo-harness-example -am package exec:java \
  -Dexec.mainClass=dev.duo.harness.example.agentrepl.AgentReplMain
```

| 场景 | 输入 | 预期输出 |
|---|---|---|
| 一、清单注入 | （启动） | system 组装含技能清单片段与 AGENTS.md 片段（叙述行可见注册） |
| 二、模型自触发 | `帮我按发布规范写个说明` | `[调工具] skill {"name": "release-notes"}` → `[工具结果]` 指令全文 → 模型按技能指令产出 |
| 三、用户直调 | `/release-notes 0.3.0` | 该次回答遵循技能指令（无需模型自选） |
| 四、AGENTS.md 生效 | 触发与项目约定相关的问题 | 模型回答体现 AGENTS.md 中的约定 |
| 五、计划模式 | `/plan 把示例目录整理成两份文档` | 计划指导生效（模型先探索）→ `exit_plan_mode` 呈交计划 → 批准/打回两态 → 批准后执行 |
| 六、fail-closed | 计划复核时不作答（回车） | 计划不批准，模型可见原因 |
