# agent-runtime（ZCode · Agent 循环与状态机）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑第 3 批产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；权限见 [../沙箱与权限.md](../沙箱与权限.md)。

## 机制全貌

单类 `AgentRuntime`（agent-runtime.ts:132）+ 构造器 deps 注入；方法体不在类内，由 `installAgentRuntimeMethods(AgentRuntime)`（:664，methods/index.ts）把 methods/ 下自由函数挂到 prototype，类体仅留 `beginShutdown`/`closeBrowserSession`（:327-334），对外面用 declaration merging 的接口（:337）。

- turn 循环 `runRegularTurnLoop`（methods/turn-loop.ts:43）：while(true){abort 检查 → modelStep>0 时 drain runtime 命令（:55-65）→ microcompact → autoCompact（rapid-refill 连环防护：77-98）→ MCP/工具装配+reminder 注入 → provider 投影+cache-control → `turnMachine.startModelRequest`（:187）→ `runModelBackedTurnStep`（:205）}，text-only 收口返回 "break"。
- command queue（command-queue.ts:144）：数组+priority now/next/later（:131），按最小优先级出队；task-notification 同级整批（:184-203）；enqueue 尾部触发 drain，`runtimeCommandDrainActive` 单飞（runtime-command-queue.ts:23-40）。steer 不进此队列：`steerTurn` 直接 push 进 `activeTurn.pendingInputs`（steering.ts:147），在 turn 循环内消费。

## 关键流程

① **executeTurn 全链**：`executeTurn`（turn.ts:70）包成 priority:"next" 的 prompt command 入队（:76-90）→ drain → `runRuntimeCommand`（runtime-command-queue.ts:187）先 `beginForegroundExecution`（每命令新 AbortController、桥接父 signal，:396-423）→ `executeTurnCommand`（turn.ts:93）：任何 await 前冻结 modelSelection/outputStyle（:103-104）→ 建 Model→context/hook→/compact、/rewind 斜命令分流（:240-267）→ `beginActiveTurn`+TurnStarted → user 入历史持久化 → loopState → runRegularTurnLoop（无工具时 `finishModelStepWithoutToolCalls`，turn-stop.ts:157：drain guide → Stop hook 续跑判定 → complete break）→ TurnComplete → projection → resolve；finally 释放 reservation/activeTurn/abort scope（turn.ts:823-845）。

② **steer 消费**：入队校验五连（steering.ts:67-114）→ TurnSteerQueued。消费点：text-only 收口 `drainInlineGuideForNextRequest`（turn-guide-drain.ts:13）→ `drainPendingInput`（steering.ts:1140）只取 guide 子序列队首（:445 按 delivery 车道，普通 queue 不偷跑；已 reserve 跳过：1169），写 history+persist+TurnSteerDrained（:1216-1263），可切模型/换 queryId（turn-guide-drain.ts:36-56）→ aggregateResults 同 turn 续跑。普通 queue 输入留待 turn 结束后由外层队列开新轮；model-step 边界另可吸收通知/子代理消息（runtime-command-active-loop.ts:22，遇 control-only-turn 即停做屏障：37）。

③ **abort 分层**：foreground 级 `stopActiveForegroundExecution`（runtime-command-queue.ts:446，expectedId mismatch 检测、preserveQueueAutoDrainOnCancel 防 sendQueuedNow 抢占误关队列：463-466）；排队级 abort→removeById，已出队窄窗则 `markCancelPending` 记账、执行侧 consume 跳过（runtime-command-submit.ts:47-57）；循环内每步 `throwIfTurnAborted`；runtime 级 `beginShutdown` 只置闸防 teardown 事件再唤醒（agent-runtime.ts:327-334）。TurnCancelled 时 guide 回退 queue 并关 autoDrain（turn.ts:731-762）。

④ **rewind**：`rewindConversationToMessage`（rewind-message.ts:404）故意绕过队列——组合 rewind 在文件事务 commit gate 内调用，排队 /rewind 会等自己释放队列而卡死（agent-runtime.ts:622-628）。流程：buildPlan 基于持久消息选 active 分支、assistant 锚点回溯映射到所属 user prompt（:476-494）→ evaluate → apply 分支裁剪。

## 接口与参数要点

- Deps 必填 eventStore、modelFactory；可选端口有降级：permissionBroker 缺省 deny-all（:249）、toolScheduler 用 `toolConcurrency.maxConcurrency`（:250-254）、now/logger/eventSink、subagentPort 缺省自建（:291）。
- `queueAutoDrain`（:216 默认 true）：TurnError/取消且有排队输入、compact 时自动置 false（turn.ts:748,760；compact.ts:152）；`queueExternalDrainActive`（:219）在暂停队列外层 FIFO 消费窗口禁行内 drain，两者门控 `hasInlineGuidePendingInput`（steering.ts:466）。
- pendingInput reservation（Map，:213；reserve/markPromoting/release，steering.ts:590-677）+ foregroundPromotionLease（:208；idle-only busy 检查：111-140）保证指定输入的下一次出队必是它（dequeueNextRunnableBatch，runtime-command-queue.ts:72-87）。
- TurnPhase 10 态（turn-state.ts:25-36）+ `canTransitionTo` 白名单表（:230-263）；TurnResultType 6 值（:149-156）。

## 边界与坑

- 非法迁移抛 `InvalidTurnPhase` recoverable:true（turn-machine.ts:92-104）；`fail()` 直写 phase 不走校验（:299）。
- 状态不可变替换：transition 返回展开副本（:103），推进处一律 `new TurnMachineImpl(newState)`。
- 旧会话缺 modelSelection 不造默认模型、不阻断恢复（agent-runtime.ts:274-276）。
- residencyBlockingWork 需在启动 Promise 的同一同步片计数、finally 释放（residency.ts:10-18），否则 sidecar 在飞时会话被误判 idle 关闭。

## 对 duo 的启示

1. **命令队列优先级+整批合并**：Java 侧可用 PriorityBlockingQueue 三档优先级，同批后台通知合并为一轮模型调用，省请求；「行内吸收」与「外层排队」两条消费车道值得照搬。
2. **phase 状态机显式化**：把「排队→模型→工具→聚合→完成」做成 enum+白名单迁移表，非法迁移抛可恢复异常，替代散落 boolean；配不可变快照便于观测/日志。
3. **pendingInput reservation/lease 即防重复提升凭证**：虚拟线程并发下「谁有资格消费队首输入」必须单飞（Map<id,reservationId> + tryAcquire 式 lease），abort 三层（排队记账/执行 controller/关闭闸）可对应 Future.cancel+中断标志+shutdown gate。
