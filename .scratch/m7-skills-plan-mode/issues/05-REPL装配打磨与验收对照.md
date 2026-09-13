# 05: REPL 装配、打磨与验收对照

**What to build:** M7 全量装配进 AgentRepl 并完成收官件：技能四根发现 + 清单片段 + skill 工具挂载（含禁用配置演示行）、AGENTS.md 注入接线、`/plan` 与技能直调命令就绪、内置一个演示技能（示例 SKILL.md）、三路触发与计划模式端到端验收对照表、文档同步。验收场景：技能直调 / 模型自触发 skill 工具 / AGENTS.md 生效（模型遵守项目约定）/ /plan 计划复核闭环。

**Blocked by:** 01, 02, 03, 04（集成单，全部在场）

**Status:** done（2026-09-13 用户手动验收通过——M7 收官）

## Checklist

- [x] demo 装配：agent-demo.yml 增 prompts / agents-md / skills 三行（工单 01/02 已接线），禁用配置演示行就位
- [x] 内置演示技能：`.duo/skills/release-notes/SKILL.md`（dogfood 形态——本仓库即演示项目）
- [x] AGENTS.md 注入接线（本仓库项目根 AGENTS.md 即注入内容，dogfood 可观察）
- [x] AgentReplMainTest 扩展：技能直调注入断言（工单 03）+ PlanModeTest 状态推导 1 例
- [x] 验收对照表（本工单 Comments）：运行命令 + 预期输出逐段对照
- [x] 文档同步：运行Demo.md 补 M7 段；CHANGELOG 未发布段收口（技能/直调/plan-mode/装配四条）；CONTEXT.md 词汇表补 5 词条（技能/发现根/计划模式/计划呈交/AGENTS.md 注入）
- [x] 用户手动验收（done 的定义）

## 实现记录（2026-09-13）

- 演示技能采用 dogfood 形态：`.duo/skills/release-notes/`（本仓库即演示项目，agent 可见自身仓库的技能目录约定）
- M7 验收对照表已写入 Comments（六场景）；验收建议在 duo-harness 仓库内跑（dogfood 完整体验，工程技能噪声用 disabled 配置讲解）

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
