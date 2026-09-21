# Web 呈现（ZCode）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑工单 16（T-20）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；组件清单见 [../总览.md](../总览.md)；分页/断线见 [../Agent循环与会话/投影、恢复与分页.md](../Agent循环与会话/投影、恢复与分页.md)。

## 机制全貌

agent 事件经 host 连接转为 v4 wire 帧，transport 内两级原子化：`ackActivationBarrier` 按 topic 暂存 physical wire 直到 subscribe ACK 激活（agentConversationTransport.ts:162-164），再由 `TopicWireDecoder` 把 base64 分片拼成 logical frame（topicWireDecoder.ts:22）；帧流入 per-session `ConversationProjectionStore`（conversationProjectionStore.ts:278）——它是唯一事实容器，UI 只读，经 `useSyncExternalStore` 订阅（useConversationProjection.ts:21），row 级 selector 在组件内做。工具卡按 `toolIdentity→renderer` 注册表分发（resolveRenderer.ts:57）。要点：

- store 遵守三条规则：snapshot 整体替换、delta 仅在 `fromSeq===seq` 时衔接、断档携水位重订（conversationProjectionStore.ts:2-6）。
- 乐观命令只是 overlay，不产生 conversation 事实（:7）；持久恢复线索独立存 `pendingCommandRegistry`。
- 渲染层纯函数分流，ToolCallBlocks.tsx 单向依赖 resolveRenderer.ts（resolveRenderer.ts:5-6）。

## 关键流程

① **wire 帧消费**：subscribe 先 handshake+TTFT 校准（queryConversationCommandsV4 `clock:true`，agentConversationTransport.ts:108-122；内容帧到达即刷新过期校准：151）；`barrier.begin→bind(subscriptionId)→activate` 释放暂存帧（:202-261、ackActivationBarrier.ts:54-96）；暂存上限 1024 帧/32MB，越界整批清空并 fail subscribe（:12-14、117-127）。decoder 30s 超时用单 timer 驱动（topicWireDecoder.ts:63-78），fault route fail-closed，仅 publisher 标记的 exact recovery 可解门（:83-90）。deliveryKind 三态：initial/recovery/online。

② **投影 store**：state 字段 status/snapshot/subscriptionId/optimisticCommands/loadingOlder/sessionPlans/planDirectoryRevision/turnNavigatorDirectoryRevision（:105-130）。applyFrame：snapshot 整体替换并 `subscriptionHasAppliedBase=true`（:653-693）；迟到帧 `toSeq<=seq` 静默丢（:698）；断档不猜——fresh subscribe 的 resume 仍断档则丢 base 强制 snapshot，否则 same-sub recovery（:702-716）。recovery 是单飞状态机（:297-307），resync 携 `{logEpoch,seq}` base（:841-849），`notOwned` 特判转 fresh connect（:866-873）。乐观对账 `reconcileOptimistic`：快照 pendingCommands + queue.sourceCommandId + userInput.sourceCommandId 命中即退场（:1265-1288）；sendText 另有 2s 投影看门狗防 ACK 后静默（:43、1218-1245）。loadOlder 以窗口首行 rowId 为游标前插（:958-997）。

③ **工具卡分发**：先组卡（changesGroup/executeGroup/cuaGroup，resolveRenderer.ts:58-66），再按工具名分流未登记 workflow 工具（防掉 raw JSON 兜底，:77-116），node-repl 先于通用 MCP（:121-126），然后 family switch（plan-guidance/agent/todo/ask-user-question/message/task-control/skill/workflow/session-context/file-read/file-write/explore/switch-mode/search/shell/goal，:131-169），default `FallbackToolCallBlock`。identity 由 `resolveToolCallIdentity` 从 toolName/kind/_meta 多源解析（lib/toolIdentity.ts:29、200）。

④ **流式 markdown**：`MessageResponse` 内嵌 Streamdown（message.tsx:1620-1655）：`mode` 区分 streaming/static——完成态一律 static，避免 remend 把 `./src/**/*` 误补成 `**`（:1628-1633）；插件 `{cjk, code, math, mermaid}`（:428），CJK 插件拼接 remark-cjk-friendly-gfm-strikethrough（:419-425）；代码块换成自研稳定 CodeBlock 防 React #185（:1636-1638）；主题切换靠改 `key` 强制重挂载（:1623-1627）；`animated=false` 防历史文本闪烁（:1651）。

⑤ **乐观交互**：`createCommandEnvelope` 用 uuidv7 commandId（重试不变）、localStorage 持久 clientId，CAS 命令缺 baseRevision 直接抛（commandFactory.ts:54-59）。SessionPane.dispatchCommand：先 `pendingCommandRegistry.record` 再 `store.markCommandPending` 后上行（SessionPane.tsx:1428-1461）。registry 对账：applyAck（accepted/duplicate 清 recovery；inputDiscardedOnRestart 按 delivery 判定 settle 或 markRecovery，pendingCommandRegistry.ts:165-187）、query 批量对账 unknown 静默清账（:193-197）、snapshot 内 queue/userInput/compact-marker 命中即结算（:229-254）。

## 接口与参数要点

- store API：`connect({forceSnapshot})`、`handleFrame(frame,{deliveryKind})`、`markCommandPending/settleCommand`、`expectAcceptedInputProjection`、`loadOlder(limit)`、`refreshPlans`、`recoverFromStaleAuthority`。
- transport：`subscribe({topic, base:{logEpoch,seq}})→ack{subscriptionId, mode:"snapshot"|"resume", openTiming}`；`resync` 同 base、可 `forceSnapshot`。
- CommandEnvelope：commandId/clientId/sessionId/baseRevision/baseLogEpoch/type/payload/issuedAt。
- 渲染上下文 `ToolCallBlockRenderContext`（shared.tsx）由 ToolCallBlocks.tsx:73 装配，theme/codePreviewSettings 由宿主注入（:109-112）。

## 边界与坑

- **断线恢复**：runtime 回收走 [250,1s,3s] 有界退避重订（:34、536-550），耗尽才落 error；`pendingCommandRegistry` 只存「客户端恢复线索」，绝不自动重放（:1-3），仅 `recovery:"discarded"` 进 UI 横幅，重放须用户确认 `consumeReplay`（:269-287）。
- **staleAuthority**：`proto.staleLogEpoch/staleRevision/staleTarget` 统一转 same-sub recovery（staleAuthorityRecovery.ts:1-19）。
- **row.removed 与分页**：loadOlder 在途若游标行被裁剪/快照替换，结果整体作废防复活已删行（:981-983）；sessionPlans 按 removedFromRowId 同步裁剪（:731-737）。
- 暂存溢出只自动 forceSnapshot 一次防风暴（:479-485）；过期代际 ACK 立即退订（:424-437）。
- 主题 token：`--color-*` CSS 变量 + `.dark` 类（styles.css:158-172、308），shikiTheme 经组件 key 重挂载同步。

## 对 duo 的启示

- **渲染器注册表协议（M24 直接蓝本）**：ZCode 用 `toolIdentity{toolName,family}` 两级解析 + family switch + 按名优先 + Fallback 兜底（resolveRenderer.ts:131-171），把「未知工具渲染成 raw JSON 卡」变成显式兜底而非魔法串判断。duo M24 把魔法串判定改结构化，可直接照搬：`family` 枚举 + `resolve(identity)->renderer` 纯函数 + fallback。
- **乐观命令对账**：`markCommandPending` overlay + 权威投影 sourceCommandId 锚点对账 + localStorage 持久账本三分收口（ACK/query/snapshot，pendingCommandRegistry.ts:165-254）。duo M8 静态单页若引入交互，这套「命令上行前登记、权威投影命中即退场、unknown 静默清账」是现成模板；CAS baseRevision（commandFactory.ts:54）也是 duo 未来多端编辑的参考。
- **投影 store 的 snapshot+overlay 结构**：三条规则（整体替换/断档不猜/水位重订）+ useSyncExternalStore 只读订阅，使状态机与 React 解耦。duo 现 M8 无此层，若 M24 起做实时呈现，建议先落这三条不变量再谈 delta。
