# M7 Spec：agent skills + plan-mode + AGENTS.md 注入

> ADR-0007 v3 渐进序列第五站。M6 让人能对 agent 说"不"、agent 能向人提问，M7 让 agent **有可复用的能力包、有先计划后执行的纪律、知道项目的规矩**。三者的共同底座是 M6 刚立的 prompt 注册表（挂载点）与交互 seam（审批通道）。参照物：DSH 的 skill / plan-mode / agent-instructions 三域（[docs/research/DSH/核心功能全景.md](../../docs/research/DSH/核心功能全景.md)）。
>
> **过程记录**：M6 理解关卡延后补考（用户裁定，见 .scratch/m6-hitl-cli/comprehension-pending.md），不阻塞 M7；M3/M4 关卡同样缺失，建议 M7 收官前一并补齐。

## Problem Statement

M6 之后 harness agent 能被治理、能问人，但三个成长性缺陷依旧：① 它每次会话都从零开始——流程知识（"如何做代码评审"、"如何写发布说明"）无法沉淀为可复用的能力包，也不能按需加载；② 面对复杂任务它直接动手，缺少"先探索、先计划、人批准、再执行"的纪律；③ 它不知道项目规矩——仓库里的 AGENTS.md（约定、红线、术语）对运行时 agent 完全不可见。

## Solution

三个组件（全部落在 agent 域，不立新模块——依赖规则强制：编排层物件依赖 llm+session+tools 恰好全覆盖）：

1. **技能系统**：`SkillRegistry` 按四根发现扫描（`.duo/skills` → `.agents/skills` → `~/.duo/skills` → `~/.agents/skills`，同名高优先根胜），解析 SKILL.md 目录包与单文件 `<name>.md`（frontmatter：name + description），启动时加载并聚合清单片段进 prompt 注册表；模型经 `skill` 工具按名加载指令全文（走六段管线）；用户经 REPL `/name` 直调（该次 send 前注入指令全文）；yml 支持禁用指定技能。
2. **计划模式**：`/plan [任务]` 进入、`/plan off` 退出；状态为会话事件 `plan/mode`（续接恢复、审计可回放）；激活时挂"计划指导片段"（引导式——不硬禁工具，写操作由 M6 交互审批兜底）；模型完成后经 `exit_plan_mode` 工具呈交计划，走交互 seam 计划复核（批准=退出执行 / 打回=带反馈继续；fail-closed）。
3. **AGENTS.md 注入**：加载 `~/.duo/AGENTS.md`（用户全局，可选）+ 项目根 AGENTS.md（.git 定根），64KB 总预算截断，注册为 `agents-md` 片段（排在用户配置片段之后）。

## User Stories

1. As a 技能作者, I want 用一个目录加 SKILL.md（name + description）定义技能, so that 我不需要写代码就能给 agent 沉淀流程能力
2. As a 技能作者, I want 单文件 `<name>.md` 也能成为技能, so that 轻量技能不必建目录
3. As a harness 使用者, I want 项目级与用户级技能目录分离且项目优先, so that 团队共享技能与个人技能互不干扰
4. As a harness 使用者, I want `.agents/skills` 也被识别, so that 我已有的行业通用技能资产直接复用
5. As a 模型, I want 在上下文里看到全部可用技能的名称与描述, so that 我知道何时该加载哪个技能
6. As a 模型, I want 用 skill 工具按名加载指令全文, so that 指令按需进入上下文而不是全部挤占预算
7. As a harness 使用者, I want 输入 `/技能名` 直接唤起技能, so that 我点名的能力立即生效
8. As a harness 使用者, I want 在 yml 里禁用某个技能, so that 不想要的能力（包括行业目录里的噪声）可以关掉
9. As a 复杂任务使用者, I want 用 /plan 让模型先探索设计、呈交计划, so that 大动作之前我先看到方案
10. As a 计划复核者, I want 收到计划后选"批准执行"或"打回并给反馈", so that 计划由我把关
11. As a 计划复核者, I want 不回答时计划不被批准, so that 离开终端绝不会让模型擅自执行
12. As a harness 使用者, I want 重启续接会话后计划模式状态恢复, so that 中断不丢失工作形态
13. As a 项目成员, I want 项目根 AGENTS.md 的约定自动进入 agent 上下文, so that agent 遵守仓库规矩而无需每次口述
14. As a 用户, I want ~/.duo/AGENTS.md 的个人偏好对所有项目生效, so that 个人习惯一处配置
15. As a 框架维护者, I want M7 全部落在 agent 域, so that 不新增模块、依赖方向不变
16. As a M8 Web 面开发者, I want 计划复核走交互 seam, so that Web answerer 无缝接管呈现

## Implementation Decisions

- **模块位置**：全部 agent 域（SkillRegistry / skill 工具 / AGENTS.md 加载器 / exit_plan_mode / plan 状态），不立新模块——依赖规则强制（放 tools 域成 tools→agent 环，M6 已验证此红线）。
- **技能发现**：四根优先级 `.duo/skills`（项目）→ `.agents/skills`（项目）→ `~/.duo/skills`（用户）→ `~/.agents/skills`（用户），同名高优先根覆盖；`.git` 定项目根（与 DuoHome 约定一致）。已知风险入档：duo-harness 本仓库的 `.agents/skills` 是 12 个工程流程技能，在本仓库运行会被当运行时技能加载（dogfood 噪声）——禁用配置兜底。
- **技能形态**：目录包 `<name>/SKILL.md` 与单文件 `<name>.md` 双形态；frontmatter 只认 `name` + `description`（description 注入清单供模型判断；先窄后宽，whenToUse 等字段按需再加）。**不做热加载**——仅启动扫描（用户定案）。
- **三路触发**：① 清单片段——启动后聚合全部"名称 — 描述"为单个片段注册进 prompt 注册表；② `skill` 工具——ToolDefinition（参数 `name`），执行 = 按名取指令全文返回（附"请遵循以上技能指令"提示），走六段管线不豁免；③ 用户直调——AgentRepl 输入 `/name` 前缀匹配技能 → 该次 send 的用户消息前注入指令全文（文本前缀注入，不做通用命令框架）。
- **禁用配置**：技能装配行 `config: {"disabled": ["name", ...]}`——命中禁用表的技术能不加载、不进清单。
- **plan 状态**：会话事件 `plan/mode`（text = entered/exited）；激活时 prompt 注册表挂计划指导片段（"先探索再设计、不做修改性操作、完成后调用 exit_plan_mode"）；**引导式不硬禁**——硬禁写操作需权限预设（M9+），当前防线是写操作的交互审批（M6）。
- **exit_plan_mode 工具**：参数 `plan`（计划全文 markdown）；执行 = 经交互 seam 发起计划复核（选项固定"批准，开始执行" / "继续计划，我要给反馈"）；批准 → 写 `plan/mode exited` 事件、结果告知模型开始执行；打回 → 结果携带用户反馈继续改计划；无回答者 / 未作答 → fail-closed（计划不批准，模型可见原因）。
- **AGENTS.md 注入**：`~/.duo/AGENTS.md` + 项目根 AGENTS.md（.git 定根；无 .git 则仅用户全局）；64KB 总预算，按"用户全局 → 项目根"拼接后超限截断尾部并注明；注册为 `agents-md` 片段，排在用户配置片段之后、其他插件片段之前。
- **启动加载时点**：技能扫描与 AGENTS.md 加载都在装配阶段（AgentRepl run 内、prompt 注册表构建时）完成——用户定案"启动时必须加载"。

## Testing Decisions

- **只测外部行为**：观测量 = 扫描结果的技能清单、组装后的 system 串、ToolsService.execute 的结果、会话事件序列、REPL 注入后的用户消息文本；不窥扫描内部实现。
- **测试 seam（5 个，3 个复用）**：
  - SkillRegistry 扫描（**唯一新 seam**，被测契约）：临时目录技能夹具——四根优先级覆盖 / 双形态解析 / 禁用生效；
  - ChatRequest 捕获（先例 ToolCallingAgentTest）：清单片段与 AGENTS.md 片段进 system 组装、顺序正确；
  - ToolsService.execute（先例 ToolsServiceTest）：skill 工具按名返回全文、缺 name 点名；
  - 会话事件往返（先例 SessionTest）：plan/mode 事件落盘与状态恢复；
  - 交互 seam 装配（先例 InteractiveApprovalTest）：exit_plan_mode 批准 / 打回 / fail-closed 三态。
- **先例清单**：MockOpenAiServer、ToolCallingAgentTest、SessionTest、InteractiveApprovalTest、AgentReplMainTest（REPL 文本断言）。

## Out of Scope

- 技能热加载（文件监听 + 缓存失效）——用户定案出圈，M9+ 候选
- CLAUDE.md 兼容——用户定案出圈（AGENTS.md 是 duo 生态唯一标准）
- 通用斜杠命令框架——M8 前置，本期技能直调走文本前缀注入
- AGENTS.md 嵌套目录链与 fs 操作后增量发现——M9+ 候选
- 硬禁写操作 / 权限预设 / 沙箱——M9+（当前计划模式为引导式，写操作由 M6 交互审批兜底）
- bundled 内置技能；技能的 Web 管理面（M8+）

## Further Notes

- 挂载点依赖：技能清单片段与计划指导片段都走 M6 prompt 注册表；计划复核与打回都走 M6 交互 seam——ADR-0008"机制与呈现分离"的第二次复利兑现（M8 Web answerer 无缝接管）。
- 文档同步义务（提交前核对）：CHANGELOG 未发布段（技能系统 / plan-mode / AGENTS.md 注入三块）、运行Demo.md（M7 段）、CONTEXT.md 术语（技能 / 发现根 / 计划模式 / exit_plan_mode / AGENTS.md 注入）、config.mts（无新 ADR 则不动）。
- 已知风险：duo-harness 本仓库 .agents/skills 的工程技能会被 dogfood 加载（噪声）——禁用配置兜底，验收建议在临时目录跑。
