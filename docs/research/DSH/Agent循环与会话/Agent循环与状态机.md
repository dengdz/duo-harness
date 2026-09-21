# agent-loop（DSH · Agent 循环与状态机）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑第 3 批产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；沙箱与权限见 [../沙箱与权限.md](../沙箱与权限.md)。

## 机制全貌

事件溯源式 Agent 循环：会话日志是唯一事实来源，恢复=重放。

- `AgentLoop`（agent-loop/src/index.ts:359）：cordis Service + `AgentFactory`，注入 agents/sessions/llm/tools/systemPrompt/sessionProjections（index.ts:360）；经 `FactoryOwnership`（index.ts:97）跟踪全部 live agent 的 teardown，`prepare()`（index.ts:531）构造驱动器并 memoize 一份反向销毁。
- `ReactLoopAgent`（agent-loop/src/agent.ts:72）：每会话一个状态机，`Phase = idle|maintenance|running`（agent.ts:41），实现 dsh-agent 契约的 send/followup/steer/inject/cancel/whenIdle/runMaintenance。
- `ReactLoopInbox`（inbox.ts:74）：inbox 是投影 `'inbox'`（inbox.ts:27），由 `agent/inbox/spliced` 事件折叠重建（inbox.ts:31-56）；每次变更先 `session.append` 落库再发 live 事件（inbox.ts:235-241）。
- `turnBoundary` 投影（index.ts:56-94）折叠 turn/step 边界供重启续跑；派发器 `agentEvents`（agent.ts:103）提供 waterfall/serial/emit 三种事件模式。

## 关键流程

① **一次步进**：`kick` 循环 `while(await turn())`（agent.ts:228）→ `turn/start`（agent.ts:279）→ `preStep`：`inbox.claim`（agent.ts:245；next-step 全取 + next-turn 取 1 条，inbox.ts:109-114）→ systemPrompt 组装 → `agent/pre-step` waterfall（agent.ts:250）→ `step/start`（agent.ts:303）→ `step()`：`agent/request` waterfall 定 config（agent.ts:531）、落 `request/header`（agent.ts:571-582）、`llm.stream` 逐 chunk 落 `AssistantStreamAttempt`、定稿 `assistant/message`（agent.ts:475-484）→ 有 tool-call 则 `executeToolCalls`（agent.ts:489）→ finally `step/end`（agent.ts:313）→ 若该步终结 turn 且 nextStep 空，先 serial 发 `agent/turn-stopping`（监听器可 steer 续命），再 break → finally `turn/end`（agent.ts:340）。

② **三种输入**（runtime-types.ts:217-241）：`followup`=next-turn+wakeup（独占自己整个 turn）；`steer`=next-step+wakeup（running 时下一 step 边界注入，idle 则开新 turn）；`inject`=next-step 不唤醒（仅搭车后续步进）。取消后到达的唤醒输入重分类为 next-turn（agent.ts:131-133）。

③ **resume**（index.ts:859-942）：先 `persistence.open(id,'write')` 抢写柄（index.ts:894-899）排除并发 resume；冷读全量日志，`interruptedTurnClosers` 补合成闭合事件（缺 tool 错误/step/end/turn/end）作为普通批次 append（index.ts:904-908）；作为 seed 建 Session。配置驱动的 restore 仅在 `SessionPersistenceNotFoundError` 时回退 create，其他错误保持响亮（index.ts:493-499）。abort 用 `AbortSignal.any(调用方, owner fiber, factory)` 三源融合（index.ts:882）。

④ **cancel**（agent.ts:149-155）：默认 `inbox.clear()`（next-step 先于 next-turn，inbox.ts:98-101）并清 wakeRequested；非 idle 则 `phase.abort.abort(cause)`，首个 cause 胜出。中断流：已流出块以 `interrupted:true` 落 `assistant/message`，无内容则落 `assistant/attempt`（agent.ts:403-431）；未派发工具补合成错误结果（`TOOL_ABORTED_BEFORE_DISPATCH`，tool-calls.ts:97,250-260），已启动调用 drain 后按模型序提交（tool-calls.ts:238-243）。

## 接口与参数要点

- `AgentLoop.create(id, options, meta)`（index.ts:704）/`createAgent(ownerCtx, options)`（index.ts:766）/`resume(ownerCtx, options)`（index.ts:859）。
- `maxParallelToolCalls` 热生效：config getter 每次读 settings `source()`（index.ts:389-399），`runGroup` 开头解构（tool-calls.ts:132），下一组即生效、不打断在飞组；settings validate 拒非法值保住旧 cap（index.ts:400-412）。
- `turn-stopping` 派发条件：`turnEnds !== null && inbox.nextStep.length === 0`（agent.ts:316-320），serial 模式，数据决定续跑与否。

## 边界与坑

- 核心执行事件必须被 turn 包裹：session invariant `appended outside any open turn`（session/src/invariant.ts:156-157）；turn 重入禁止（invariant.ts:73-74）。
- `Session.append` 禁止重入：接受/发布边界打开期间再入直接拒绝（session/src/index.ts:715-717）。故 `send` 在插入 inbox 前先捕获 wakingAfterAbort，防 splice 观察者里的重入 cancel 重分类（agent.ts:130-133）。
- disposed 语义：teardown=`cancel({kind:'disposed'})`→whenIdle→scope.dispose（index.ts:595-598）；disposed 期间唤醒不 latch 不开新 turn（agent.ts:193-196），wake 落 abort 窗口则降级 next-turn 或 latch 待收敛重放（agent.ts:188-198）。
- 工具屏障：exclusive 调用单独成组；parallel 池滚动补位，途中遇非 parallel 调用即 break 留给下一屏障（tool-calls.ts:199-214）；结果/附加上下文严格模型序 commit（tool-calls.ts:147-161）；调度器内部失败不伪造结果、drain 后抛首错（tool-calls.ts:232-236）。max-tokens 结果粘滞不随后续完成降级（agent.ts:308-311）。

## 对 duo 的启示

1. **inbox 两级队列+落库**：next-turn/next-step 优先级与 splice 事件持久化，duo（Java 虚拟线程）可用阻塞队列+DB 表实现，step 边界消费、宕机可重放，steer/inject 语义照搬。
2. **resume 抢写柄**：`open(id,'write')` 独占写锁排除并发 resume，仅 NotFound 回退新建——duo 用 DB 行锁/文件锁即可转译，避免双实例同写会话。
3. **取消保留部分输出**：中断保留已流出文本（interrupted 标记）并为未启动工具补合成错误结果，保证日志可重放无悬空 tool-call——duo 取消时应 flush 半截输出+补齐工具占位结果再关 turn。
