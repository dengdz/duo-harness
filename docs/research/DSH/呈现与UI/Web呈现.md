# Web 呈现（DSH）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 16（T-20）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；组件清单与路由交互见 [../总览.md](../总览.md)。

## 机制全貌

DSH 前端是一条「事件→注册表→槽位」三级总线：session 事件流先经 ui-conversation 的 **Definition 注册表**折叠为业务 Context，再由各 target 的**视图注册表**增量产出节点，最后经 ui-slots（single/list/keyed/chain 四种槽 × root/session-maybe/session 三种作用域，ui-slots/src/index.ts:101-104）分发给 React 组件。工具卡走 keyed-by-toolName 的 `tool.call.toolview` 插槽（未注册自动 generic 兜底）；composer 输入位是 chain 槽，审批/提问卡通过「pendingInteraction 载体 + select 选举」接管输入位。dockkit 提供独立 React-free 布局引擎，主题靠 index 注入的头 CSS+脚本保证首帧无白闪。

## 关键流程

① **事件→节点**：`ConversationNodeDefinition` 声明 match/start/update 纯函数（ui-conversation/…/contract/conversation.ts:185-245）；match 返回 `{id, role:'start'|'update'}`，Assembler 按 `conversationContextKey(kind,id)`（conversation.ts:295-297，长度前缀防碰撞）折叠。`append()` 只增量匹配新事件并回放依赖（assembler.ts:224-243），`replaceWindow()` 整窗重放（:190-211，对应分页/修复）；发布节奏由 Definition 的 `publication()` 声明（none/animation-frame/immediate，:179、assembler.ts:55）。ui-chat 注册 13 类 Definition + chat target builder（conversation-nodes/register.ts:21-36）；节点 key=Context key，排序用 anchorSeq，interrupted/maxTokens 等合成节点用小数偏移插入（common.ts:14-20）。

② **工具卡**：toolDefinition 把 tool/call 定为 start、tool/result 与 tool/ptc-dispatch(按 rootCallId) 定为 update（tool.ts:236-248）；PTC 子调用树以 children/parents Map 构建，acceptsEdge 防环、MAX_DEPTH=256（tool.ts:18,117-140），projectBlock 用 WeakMap 缓存投影并合成中断 result（:170-208）。渲染层 ToolCall 递归分发：`renderSlot('tool.call.toolview', owner, {entryKey: toolName, fallback: GenericToolCard})`（ToolCallTree.tsx:44-49）；keyed 槽重复 key 抛错、每 cell 取首个 live entry（ui-slots index.ts:1225-1229,1337-1356）。

③ **composer 接管**：chain 槽 entry 携 `select(owner)=>matched|null`，renderSlotChain 低 priority 先试、命中渲染接管组件并传 matched，否则渲染 fallback composer.bar（ConversationContent.tsx:166-170）。载体=uiSession.registerPendingInteraction 发布的 PendingApproval（priority 1）与 PendingQuestion（plan-review=2/question=1），均挂 Remote waterfall；delegate/abort 时 `next()` 传递并 `remove()` 释放输入位（ui-approval/index.ts:53-67,80-89；ui-user-questions/index.ts:87-99）。

④ **流式渲染**：assistant/live-chunk 增量写入 assistant-step 的 blocks（assistant.ts:93-118）；MarkdownText 用 IncrementalMarkdownParser——冻结块缓存元素、每帧只重解析尾部，流毕切 full parse 自愈引用（ui-primitives/markdown/MarkdownText.tsx:5-13,88-109），这是防闪烁核心；图片组以首块索引为 key 防重挂（AssistantMarkdown.tsx:99-118）。

⑤ **store**：zustand vanilla+immer+subscribeWithSelector+rafBatch 引擎（store/src/index.ts:1-13,103-136）；`defineStore(init,persist?,actions表)` 产 handle，actions 是纯 draft 变换表=唯一写面，组件只见 useStore+actions（contract.ts:40-49,134-136）；实例按 handle×scopeKey 缓存、persist key 加会话后缀。业务快照（ChatSnapshot）本身是 ObservableSnapshot+keyed useChatNode hook（ui-chat slots.ts:25-31），组件 store 只存 UI 态。

## 接口与参数要点

- 槽位清单：`conversation.chat.node`（keyed by ChatNodeKind）、`conversation.chat.commandview/turnTail/assistant-actions`、`conversation.message.images`、`tool.call.toolview`（keyed）、`tool.call.images`（子槽，单一声明）、`conversation.composer`（chain）、`conversation.composer.bar`、`conversation.input.dock/overlay/left/right/plan/permission/model`、`conversation.view`（list）等（ui-chat/contract/slots.ts:182-220；ui-conversation/contract/slots.ts:124-211）。
- register 签名：`{name, key|id, priority, order, select, locale, store, children, inject}, component`；keyed 缺 key/重复 key、chain 缺 select 均抛错（ui-slots index.ts:1222-1237）。优先级升序稳定排序、同 priority 保注册序，list 另以 order 细排（index.ts:1267-1273）。

## 边界与坑

- key 域开放无编译期校验：toolview「typo simply never renders」（ui-tool slots.ts:15-18）。
- 乐观对账：inputs 按 seq 去重（assembler.ts:226）；attempt 结束先 retire 瞬态 match 再落 durable settlement；合成 seq 偏移节点需与真实事件序区分。
- 槽作用域：children 由父 entry 独占声明、二次声明抛错，注销连带释放（index.ts:1238-1256,1590-1610）；chain 崩溃不退位、shadowing 崩溃一次性退位（renderer.ts:166-174）。
- store：同 persist key 多实例互串是文档化已知边界（store index.ts:186-194）；raf flush 下帧内挂载者读新态、订阅者下帧才收到（:93-97）。

## 对 duo 的启示

对照 duo 现状（M8 静态单页 app.js 1389 行、M13 尾窗快照+分页、前端魔法串判定、M24 待协议化）：

1. **事件→节点注册表协议**：把「事件类型→折叠函数→节点」抽成 Definition 表（match/start/update+稳定 key 规则），M13 分页正对应 assembler 的 replaceWindow/append 双轨，可整体替换散落 if/else——M24 最直接的蓝本。
2. **工具卡键控插槽**：`tool.call.toolview` 的 keyed-by-toolName+generic fallback+子槽单一声明，可直接替换 duo 前端魔法串 switch，新工具可加卡可接管。
3. **composer 接管协议**：pendingInteraction 载体+chain select 选举+delegate waterfall，把审批/提问从「改 DOM」变为「输入位协议」，priority 即优先级。
4. **store 规约**：defineStore 的 actions 表把写路径收敛为审计面，duo 全局 state 宜先落此形态再谈组件化。
