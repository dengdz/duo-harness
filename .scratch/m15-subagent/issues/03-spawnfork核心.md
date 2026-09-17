# 03: spawn/fork 核心——内嵌子代理与播种

## What to build

父 agent 获得任务分解核心能力（ADR-0015 决策 1/2/3/4）：`agent.subagent` 子包——后端接口（仅同进程内嵌实现，跨 harness 留接口位）+ 子代理注册表 + 子会话生命周期（独立 JSONL、独立进程锁、子目录存放且天然不进侧栏列表）+ `spawn`（模板名 + 任务描述，全新）/ `fork`（另播父历史）双工具，后台异步立即返回 agent id，子 agent 于后台虚拟线程运行，完成写父会话 `subagent/completed`（最终回答进父投影）。fork 播种 = 父日志平衡完成轮前缀（到最近完成轮止，半截轮排除）逆写为子会话开头段 + 种子边界（"前 N 条来自父日志"）。

## Blocked by

01 会话域 subagent 事件、02 模板装配与工具接线

## Status

done（2026-09-16 用户验收：功能演示 + 全量测试双路径通过）

## Checklist
- [x] 后端接口 + 内嵌实现（子 agent 构造：父 adapter 继承、治理同父配置、模板工具集）
- [x] 子会话生命周期：独立锁、子目录、侧栏天然排除（断言）
- [x] spawn/fork 工具：立即返回 id、后台虚拟线程运行、completed 回流父会话
- [x] fork 播种：平衡前缀切点（半截轮排除用例锁定）+ 种子边界记录
- [x] 端到端：mock LLM 全链（父 spawn/fork 子 → 子调工具 → 完成 → 父投影收到结论）

## 实现记录

**新类（agent.subagent，7 个）**：
- `SubagentBackend`（接口，嵌套 Task/Outcome record）：跨 harness 留位，本期仅内嵌一种；`run` 阻塞语义，异步性归管理者（虚拟线程）。
- `EmbeddedSubagentBackend`：子 agent 复用父 LLM adapter 与治理 tuning（治理**独立实例**——ContextGovernance 非线程安全，子代理后台线程与父循环并发）；工具经过滤视图只见模板可用集；system = 模板专属提示（子任务自包含，不继承父提示片段）。
- `SubagentManager`：注册表（Entry：id/模板/状态）+ 生命周期。spawn/fork 立即返回 id；子会话建在 `<sessionsDir>/subagents/`（Session.list 非递归 → 侧栏天然排除）；后台跑完 completed 回流父会话（失败也回流、标注"未正常完成"——父 LLM 可感知后调整）；finally 释放子会话锁（完成后可打开回放全程）。spawn/fork 方法签名带 backend 参数——无可变 bind。
- `SeedSlicer`：平衡前缀切点 = 最后一个 assistant/message（含）——它只在轮正常收尾时写入，其前必轮轮平衡；悬空 user / 孤儿 tool/result 排除；无完成轮 → 空前缀，fork 退化为全新起点。
- `SubagentToolView`（包私有）：list() 过滤、execute 委托共享注册表——三段管线（审批/guard/输出契约）对子代理自然生效；工具域零改动。
- `SpawnTool` / `ForkTool`：参数只有 template + task（模板制，无配工具权力）；执行经 `Supplier<Session>` 取当前父会话。

**接线**：
- `SubagentPlugin`：模板非空 → 发布 `subagents` 服务（Manager）+ 控制面供给清单（04 挂入）；模板空 → 零副作用。
- `PresenterAssembly.registerSubagentTools`：呈现位装配时 `hasService` 探测，模板非空才注册 spawn/fork（依赖父会话与 LLM 执行链，故在呈现位；控制面在插件侧——两处共同受"模板非空"把守）。CLI/Web 调用点接线归工单 05。
- 会话域补 `SUBAGENT_SEED_BOUNDARY` 事件（text = "前 N 条来自父会话 <id>"，投影 default 跳过）。

**测试（agent 25 + session 36 全绿，全仓库 compile 零破坏）**：SeedSlicerTest 4（切点/半截轮/空日志）、SubagentManagerTest 6（latch 确定性回流、运行期持锁→完成释放可回放、子目录侧栏排除、fork 播种+边界、失败回流、未知模板点名）、SubagentEndToEndTest 2（mock 双 LLM 真闭环：父 spawn→子调工具→完成→父投影收到结论；fork 播种全链）。

**已知限制（进 06 limitations 收口候选）**：子模板若配置需审批（requiresApproval）的工具，子代理执行时照走审批管线、等待应答——部署者模板应避免放入此类工具；控制面 send_message 语义（运行中入列纠偏/空闲开新轮）归工单 04。

## 真实任务实测后的三处修复（2026-09-16，用户 CLI 实测触发）

用户用真 LLM 跑"派 researcher 子代理调研本仓模块划分"，结果子代理回报**未正常完成**。
取证（子会话 JSONL）：1 条 user + **27 对 tool/call↔tool/result** + **0 条 assistant/message**
——子代理 10 轮里做了 27 次工具调用（glob 找 pom → read 各模块 pom → grep 统计包结构，
全部成功、路径正确），在收集阶段用光轮次、未及汇总。分析出三个缺陷并修复：

**① 子代理治理管线未生效（实现 bug，偏离 ADR-0015 决策 5）**
- 原：`tuning == null ? null : new ContextGovernance(llm, tuning)` —— tuning 为 null（yml 未配
  governance 段）时子代理**完全不治理**；而父侧 `PresenterAssembly.governance(llm, null)` 是
  **用缺省常量治理**（实例非 null）。父子配置不一致，且 27 个工具结果（单个 grep 返回 38765
  字符）全量灌进子代理上下文，直接损害其收束能力。
- 修：子代理恒建治理实例 `new ContextGovernance(llm, tuning)`（tuning null = 缺省常量），
  与父严格同配置。

**② 迭代上限对委派型重活偏小（默认值调整 + 可配）**
- 原：子代理复用主 agent 的 `MAX_ITERATIONS = 10`；实测"读多模块 pom + 统计全仓包结构"
  一类任务 27 次工具调用仍在收集阶段。
- 修：`SubagentTemplate` 新增可选 `maxIterations`（严格绑定解析，非正整数点名），缺省
  `DEFAULT_MAX_ITERATIONS = 30`；`effectiveMaxIterations()` 统一取值。部署者可按任务性质调。

**③ 未完成时回流信息全丢（聚合语义补全）**
- 原：父 agent 只收到"未正常完成：agent 循环未给出最终回答"——子代理 27 份成果一份也传不回，
  且父不知道 FAILED 态其实可续轮。
- 修：`EmbeddedSubagentBackend` 未完成时用 `AgentReply.toolInvocations()` 生成成果摘要
  （逐条列"序号. 工具名 参数摘要 → 结果体量/错误" + 末次结果尾部 800 字符摘录）；
  `SubagentManager` 回流文本改为"失败原因 + 已完成的中间成果 + 可经 send_message 续跑"的指引
  （指引非空头承诺——FAILED 态确可续轮，同用例断言）。

**测试**：SubagentTemplatesTest 11→13（maxIterations 解析与缺省、非正整数点名）、
SubagentManagerTest 12→13（未完成回流带成果与续跑指引，且断言续轮真可用）；agent 34、
CLI 8、web 全模块、session 37 全绿；Demo 回归通过。

## 验收对照（应出现的套件叙述与结果，实测快照 2026-09-16）

**演示路径（功能行为可见，一条命令）**：

```
mvn -pl duo-harness-example -am compile exec:java -Dexec.mainClass=dev.duo.harness.example.subagent.SubagentDemoMain
```

实测输出（2026-09-16 快照，叙述行为核心验收点）：

```
=== duo-harness M15 subagent 功能演示（脚本化 LLM，无外部调用） ===

[装配] 模板 researcher：工具 [echo]，专属提示已注入
[装配] 工具域现有: [echo]

─── spawn：全新子代理 ───
[父 LLM] 发起工具调用 spawn(template=researcher, task=…)
  -> [调工具] spawn {"template":"researcher","task":"调研 X 的可行性"}（立即返回，不阻塞）
  <- [结果] {"agentId":"20260916-150937-ec75","template":"researcher","status":"started"}
[父 LLM] 直答：已派子代理在后台执行，稍等它的结论。
  [子代理] 调用 echo(text=中间探索过程)
[回流] 父会话收到 subagent/completed（id=20260916-150937-ec75）：
    子代理 20260916-150937-ec75（模板 researcher）已完成。
    最终回答：
    调研结论：X 可行，建议按方案 A 推进。
[投影] 父 LLM 下一轮上下文最后一条（USER 角色）：
    子代理 20260916-150937-ec75（模板 researcher）已完成。…
[锁] 子会话已完成，锁已释放=true（可打开回放子代理全程）
[侧栏] 会话列表 = [20260916-150937-a263] —— 子会话在 subagents/ 下，不进侧栏

─── fork：带着父对话背景派生 ───
[父 LLM] 发起工具调用 fork(template=researcher, task=…)
  <- [结果] {"agentId":"20260916-150937-e399",…,"status":"started"}
[播种] 子会话开头段（父平衡完成轮前缀原样落子日志）：
  [0] user/message：帮我调研一下 X 的可行性
  …
  [4] assistant/message：已派子代理在后台执行，稍等它的结论。
  [5] 「种子边界」前 5 条来自父会话 20260916-150937-a263

[文件] 会话目录：…/target/duo-subagent-demo-sessions
       父会话在顶层、子会话在 subagents/ 下——可打开 JSONL 看事件溯源原文
```

**测试路径（全量套件，用户手跑）**：仓库根目录 `mvn test`。应出现：

```
=== 套件：SeedSlicerTest —— fork 播种切点：完成轮全量、半截轮排除、无完成轮空前缀、多轮切最近（4 用例） ===
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
=== 套件：SubagentManagerTest —— 生命周期：spawn 立即返回/latch 确定性回流、运行期持锁与完成释放、子目录与侧栏排除、fork 播种与种子边界、失败回流（6 用例） ===
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
=== 套件：SubagentEndToEndTest —— 端到端全链：父 spawn 子 → 子调工具 → 完成回流 → 父投影收到结论；fork 播种全链（2 用例） ===
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

覆盖核心路径：spawn 立即返回（父会话此刻仅 spawned 事件）；子代理后台完成后最终回答回流父会话并投影进父上下文（端到端 mock 双 LLM 真闭环）；运行期子会话持锁、完成后释放可回放；子会话在 `subagents/` 子目录、侧栏列表只见父会话；fork 播种平衡前缀 + 种子边界标记（悬空半截轮排除）；后端失败以"未正常完成"回流；未知模板启动点名报错。

## 第二轮实测后的方向纠正（2026-09-16，用户指正 + 三方对照）

用户重跑（40 轮、43 次工具调用）仍"未正常完成"，并指出一个关键判断：**其他实现
（ZCode、DSH）派子 agent 没有"记忆"概念**，据此核对三方事实：

| | 派发形态 | 防跑偏机制 |
|---|---|---|
| ZCode（本 agent 自身） | 子 agent 拿自包含 prompt，干完返回总结 | 派发工具的**描述层引导**：明确"何时该派（找不到时）/何时不该派（单事实查询直接搜）"、"prompt 必须自包含"、"派了就别自己重复搜" |
| DSH | `subagent`/`subagent_fork`，参数只有任务描述与 prompt（能力由部署侧 preset 定） | 父侧 `todo_write` 任务清单 + `workflow`/`ralph`（脚本化多子 agent 并行）；**子代理同样无记忆机制** |
| duo-harness（本期） | spawn/fork + 模板制 | 无（工具描述初版未含粒度引导） |

**结论：子代理是短命的一次性执行者，不该有跨任务记忆——这是正确形态，不是缺陷。**
本轮失败的真因不是"子代理缺记忆"，而是**任务粒度失配**：父 agent 把"调研全仓模块
划分并产出报告"（数十步采集 + 汇总成文）整包甩给单个子代理，超出任何合理预算。近半
轮次耗在重复读取上，是"预算被大任务耗尽后、上下文修剪介入"的**放大器**，不是根因。

**撤回**：上一轮设想的"子代理以文件为工作记忆"（笔记文件 / 提示词要求随时落盘要点）
属过度设计——已从 `subagent-demo.yml` 模板提示词中删除，改为对 DSH/ZCode 同款的
**描述层引导**。

**已落地的纠正**：
- `SpawnTool`/`ForkTool` 描述改写：写明"子代理看不到本对话、无跨任务记忆 → task 必须
  自包含、须是有限步内可完成的一件小事 → 大任务拆成多个子代理或先自己探索定位"，
  并把"子代理有迭代上限，任务过大会中途耗尽轮次"作为显式后果提示。
- `subagent-demo.yml`：父侧 systemPrompt 加派发纪律（一件小事一个子代理，不整包甩锅）；
  模板提示词保留"结果被截断时改用更精确查询、不重复读取同一目标 + 任务范围外不深挖"，
  删除"写笔记"要求。

**记入 backlog（治理侧增强，非子代理必需）**：prune 对 grep/read 结果做保留匹配行的
结构化摘要，而非中段硬截（当前头 2000/尾 1000）——DSH 同为头尾修剪，但其配套是
80% 阈值 compaction + 父侧 todo 分解，故不常撞。另：todo/goal 类"分解抓手"工具
属后续里程碑（DSH 有 `todo_write`/`create_goal`），本期父 agent 只能靠描述引导分解。

**三方机制对照（2026-09-16 深挖，全文见
[docs/research/subagent-派发与提示词对照.md](../../docs/research/subagent-派发与提示词对照.md)）**：
核心三层分工与 DSH/ZCode 同构；四项差距记入待议——① 技能清单/AGENTS.md 对子代理不可见
（ZCode 注入 skills 清单、DSH 继承父全部 prompt 段；补齐涉模板制边界，需先裁定）；
② 子代理审批未钉死（DSH 恒 'never' + 理由回传，可作本期小改）；③ 子代理无环境块
（ZCode 注入 <env>）；④ spawn/fork 描述未按继承语义参数化（DSH providerWording）。
另：未完成回流带成果摘要为我们的独有增强（DSH/ZCode 只回最终结果）。

## 第三处纠正：子代理 system 的两层构成（2026-09-16，用户指正 prompt 来源）

用户第三个观察：**派发时的提示词是每次按场景现场生成的，不是预制的**。核对结论——
两面分工在同类实现里是清晰的，而我们此前只做对了一半：

| 层 | 来源 | 同类实现 | duo-harness（纠正前） | duo-harness（纠正后） |
|---|---|---|---|---|
| 子代理身份/通用纪律 | 框架 | 固定 agent type 描述（ZCode） | ❌ 缺失——只能由每个模板重写 | ✅ `SubagentBaseline`（框架维护） |
| 模板角色/领域约束 | 部署者 | preset / type 描述的领域化 | 模板 prompt（但混入了通用纪律） | 模板 prompt（只写角色） |
| 任务描述 | 父 agent 每次现场生成 | prompt 参数（须自包含） | ✅ spawn/fork 的 task 参数 | ✅ 不变 |

**缺口**：纠正前子代理 system = 模板 prompt（`new PromptRegistry(template.prompt())`），
框架未提供基线——于是"不要重复读取同一个目标"这类**委派形态固有的通用纪律**被写进了
每个模板的专属提示里（部署者每建一个模板都得重写一遍，且漏写就完全失效）。

**纠正**：新增 `SubagentBaseline`（agent.subagent，包私有）——基线正文承载五条委派固有约束
（无跨任务记忆/只做交接的一件事/范围外不深挖/结果被截断改精确查询不重复读取/结论即交付物，
条文均来自本轮实测的失败形态），子代理 system = 基线 + 模板专属提示；模板 prompt 回归
"角色与领域约束"。端到端用例新增断言：捕获子代理实际收到的 `ChatRequest.systemPrompt()`，
验证基线条文在场且居首。

**注**：本纠正同时收敛了上一轮的过设计——通用纪律的归属是"框架基线"（一处维护），
而不是"模板提示词 + 文件工作记忆"（多处重复 + 新机制）。
