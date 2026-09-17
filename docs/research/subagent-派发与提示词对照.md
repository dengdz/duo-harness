# 子代理派发与提示词生成机制：DSH / ZCode / duo-harness 三方对照

> 2026-09-16 M15 实测复盘的深挖（用户连续三轮指正派发形态）。证据来源：DSH 本地源码
> `/Users/zhangyl/IdeaProjects/deepseek-harness/packages/subagent/`（现行锚点 c291e796）；
> ZCode 本机安装 `/Applications/ZCode.app/Contents/Resources/app.asar` 与运行时数据
> `~/.zcode/cli/agents/`（真实子代理 transcript）。 duo-harness 侧见
> `.scratch/m15-subagent/issues/03-spawnfork核心.md` 的三轮纠正记录。

## 一、结论速览

三方在"子代理提示词从哪来"上是**同构的三层分工**，不存在"固定模板 vs 现场生成"二选一：

| 层 | DSH | ZCode | duo-harness（M15 纠正后） |
|---|---|---|---|
| 身份/委派声明（固定） | `SUBAGENT_DELEGATION_CONTEXT`（child-agent.ts 注入 runtime-context） | `system[0]` 品牌 + `system[1]` 类型描述 + `system[2]` 运行约束 Notes | `SubagentBaseline.TEXT` |
| 角色/组合（部署者定） | preset 组合继承 + per-child persona 遮蔽 | 用户自定义 subagent 类型（subagents.json 配置键） | 模板 `prompt`（yml，可选） |
| 任务描述（父现场生成） | `prompt` 参数（schema 强制） | `prompt` 参数 → `user[N]` 首条消息 | `task` 参数 |
| 环境块（动态） | cwd/lineage/delegationDepth 记入会话 meta | `system[3]` env 块（cwd/git/平台/模型） | ——（暂无，见差距） |

## 二、DSH：源码实证

**派发流程**（`packages/subagent/tool-subagent/src/index.ts` + `subagent/src/child-agent.ts`）：

1. 父 agent 调 `subagent`/`subagent_fork` 工具，参数：`description`（3-5 词简介，展示用）、
   `prompt`（任务全文）、可选 `provider/model/reasoningEffort/maxTokens` 覆盖、可选 `run_in_background`、
   可选 `persona`（需 provider capability，省略则保留部署 persona）与 `toolFilter`。
2. `resolveChildDepth` 记账委派深度（可配 `maxDepth` 上限；持久化的父深度是单调下限）。
3. `applyChildComposition(childCtx, parent, composition)` 在子代理创建窗口内做三件事——
   这就是子代理 system 的生成逻辑：
   - `agentPresets.composeFrom(childCtx, parent.ctx)`：**子代理加入父的 preset 组合**——继承父的
     全部 system prompt 段与工具清单。源码注释点名了不这么做的后果："a child that joins no
     preset sees an empty tool registry and none of its parent's prompt sections"。
   - 注入固定委派声明 `SUBAGENT_DELEGATION_CONTEXT`（原文："You are a delegated subagent: your
     permission scope was fixed when you were started and cannot be widened from inside this
     session — operations that require approval are rejected automatically. When the task needs
     access beyond that scope, do not retry the denied operation; state the limitation in your
     reply so the delegating agent can handle it."）。**形态要点**：它是 runtime-context
     contribution 而非 system-prompt section——"deployment 的 system prompt 在父子间保持
     uniform"。
   - per-child persona 遮蔽部署 persona（`deployment:persona-prefix` 同名段覆盖）+ 工具过滤。
4. 治理策略种子：审批策略**恒钉 `'never'`**（子代理的 ask 确定性拒绝），sandbox 覆写以
   `source: 'delegation'` 事件落子日志（`appendDelegatedPolicyOverrides`）。
5. fork（`subagent_fork`）：播种父日志平衡完成轮前缀 + 种子边界（`isSeeded`/`lineageSeedLength`
   记入子会话 meta）。工具描述**按 provider 的 `inheritsParentContext` 动态措辞**
   （`providerWording`，index.ts:251）——fork 版告诉模型"它已看到本轮之前的全部对话，prompt
   只写新增内容即可"；spawn 版则强调"complete, standalone prompt: it does not see this
   conversation"。

**关键源码位置**：
- 工具定义与描述措辞：`tool-subagent/src/index.ts:251`（`providerWording`）、`:381`（schema 组装）
- 子代理组合与身份声明：`subagent/src/child-agent.ts:171`（`SUBAGENT_DELEGATION_CONTEXT`）、
  `:199`（`applyChildComposition`）、`:138`（`childSessionMeta`——cwd/父会话/preset/深度/种子边界
  持久化）
- 深度与策略：`subagent/src/depth.ts`、`child-agent.ts:242`（`captureDelegatedPolicyOverrides`）

## 三、ZCode：运行时实证（真实 transcript）

证据：`~/.zcode/cli/agents/sess_00b4fe49…/agent_012df374…/transcript.jsonl`（2026-07-16 真实
派发记录，父会话派发实现类子任务）。子代理首条 `model_request` 的消息结构：

```
system[0]  42 字符   "You are ZCode, an interactive coding agent"          ← 品牌/身份（固定）
system[1]  1249 字符 "You are an agent for ZCode CLI. … Complete the task
                     fully—don't gold-plate… respond with a concise report…
                     the caller will relay this to the user…" + strengths/
                     guidelines 清单                                       ← 类型描述（固定模板，
                                                                            按 subagent_type 选）
system[2]  811 字符  Notes: cwd 每次重置（用绝对路径）/最终回复给绝对路径/
                     不用 emoji/不要写报告 .md 文件——"the parent agent reads
                     your text output, not files you create"               ← 运行约束（固定模板）
system[3]  316 字符  <env> Working directory / git repo / Platform / Shell /
                     OS Version </env> + 模型名                            ← 环境块（动态生成）
user[4]    4773 字符 <system-reminder> 可用 skills 清单                      ← 注入（动态）
user[5]    299 字符  <system-reminder> 当前日期                              ← 注入（动态）
user[6]    2435 字符 父派发的任务书（目标/约束/逐条要求/验收标准，现场生成）    ← 任务（现场生成）
```

配套事实：`toolCount: 12`（子代理工具集收窄，少于父会话）；每条 system 消息带
`cacheControl: ephemeral`（前缀缓存）；`metadata.json` 记 `agentId/childSessionId/cwd/
description`（父给的 3-5 词简介）与 `outputFile`（子代理最终报告落盘供父收取）；运行时目录
`~/.zcode/cli/agents/<父会话>/<agent_*>/`（metadata.json + transcript.jsonl + output.txt +
task.output）。asar 内可见 `subagents.json` 配置键——用户可自定义子代理类型（与
duo-harness 的模板制同构）。源码本体打包于 `app.asar`（JS 混淆，模板原文以 transcript
实证为准）。

## 四、duo-harness 对照与差距

M15 纠正后的形态：`SubagentBaseline`（固定基线）+ 模板 `prompt`（部署者，可选）+ `task`
（父现场生成）——三层齐备，与 DSH/ZCode 同构。

**尚未对齐的四点（2026-09-16 对照后记入，待定归属）**：
1. **技能清单 / 项目约定对子代理不可见（本轮对照新发现，最实质）**：ZCode 给子代理注入可用
   skills 清单（transcript `user[4]`，4773 字符的 `<system-reminder>`）；DSH 子代理经
   `agentPresets.composeFrom` **继承父的全部 prompt 段**（含 AGENTS.md、技能目录段）。我们的
   子代理 system 只有基线 + 模板 prompt——AGENTS.md 里的项目约定、部署的技能清单对子代理
   均不可见，调研类子任务可能缺项目上下文。补齐涉及模板制的边界（子代理是否/如何继承呈现位
   prompts 服务的片段），属 ADR-0015 决策 5 的细化，需先裁定再动手。
2. **审批钉死**：DSH 把子代理审批策略**恒钉 `'never'`**（沙箱范围在派发时固定，内部 ask 一律
   确定性拒绝，拒绝理由回传）。我们目前子代理走共享审批管线（已知限制记档为"模板别放需审批
   工具"）——"钉死 + 理由回传"是更干净的治理形态，可作本期小改（把子代理可用集内的需审批
   工具确定性拒绝）。
3. **环境块**：ZCode 注入 `<env>`（cwd/平台/OS）；DSH 把 cwd/lineage/深度持久化进子会话
   meta。我们的子会话 meta 只有 id/模板/状态——`cwd`（工作目录约束）对子代理同样有意义。
4. **动态措辞**：DSH 的工具描述按 spawn/fork 是否继承对话**自动切换措辞**（避免对 fork 子代理
   误述"它看不到对话"）。我们的 spawn/fork 描述已分别写明，但未参数化。

**我们独有（比两家多）**：未完成时回流部分成果摘要 + `send_message` 续跑指引（DSH/ZCode
只回最终结果，中间成果直接丢弃——"You receive its result, not its intermediate steps"）。

## 五、与既有记录的关系

- 补强 `DSH/核心功能全景.md` 第五节 subagent 行（该行只记了 spawn/fork + 六后端 + 控制面，
  未记提示词组合机制）。
- 工单 03 的三轮纠正记录（`.scratch/m15-subagent/issues/03-spawnfork核心.md`）中的
  SubagentBaseline 设计与"工具描述层引导"在本对照中获得 DSH/ZCode 双重印证。
- backlog（工单 06 收口候选）：prune 结构化摘要、todo/goal 分解抓手、子代理审批钉死、
  子代理 env 块。
