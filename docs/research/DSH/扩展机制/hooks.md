# hooks（DSH）

> 源锚点：`ddefc45fbc7f8e46dd73185e68295696d1297887`（2026-09-21 本地核验）。M22 探测里程碑工单 12（T-16）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)。

## 机制全貌

hook-protocol 定义方言中立的命令钩子契约：桥把 hooks.json 解析成 `MatcherGroup[]`（types.ts:68-71），在 Agent 生命周期拦截点同步执行 shell 命令，stdin 喂 JSON 载荷，按退出码+stdout+stderr 解码为中立 `HookOutput`（types.ts:89-137），再按点映射成 PreToolDecision/PreStepDecision 等类型决策。每次执行写 `hook/invoked`/`hook/result` 日志事件对（types.ts:19-39）。装配走 cordis 插件按名插入（snapshots/session/text-turn/cordis.yml:73-82，configPath 指向 ./hooks.json）。

- 两桥均挂在 `agent/created`(SessionStart)、`agent/pre-step`(UserPromptSubmit)、`tools/pre-execute`(PreToolUse)、`tools/post-execute`(PostToolUse)、`agent/turn-stopping`(Stop)；claude 桥多 SubagentStart/Stop（claude index.ts:202-294；codex index.ts:184-269）。
- 共享件：codec/matcher/merge/runner/detached/events 均在 hook-protocol；载荷构造与决策映射归各桥（types.ts:1-6）。

## 关键流程

① **载荷与编解码**。CC 载荷 base＝`session_id/transcript_path('')/cwd/hook_event_name`（claude index.ts:320-329），按事件加 `prompt`、`tool_name/tool_input/tool_use_id`、`tool_response`、`stop_hook_active`、subagent 的 `agent_id/agent_type`（334-359）。Codex base 另有 `model/permission_mode('default')`、`transcript_path:null`，turn 级事件加 `turn_id`（codex index.ts:290-306），`tool_input` 收成 `{command}`（317-323）。解码（codec.ts:59-89）：**exit 0**＝放行，stdout 以 `{` 开头才尝试 JSON，坏 JSON 宽松降级为纯文本（75-85）；**exit 2**＝阻断，stderr 即 reason（66-69）；**其他退出码**＝非阻断错误；**undefined**＝spawn 失败/信号死亡。结构化字段：顶层 `decision` 仅认 approve/block（38-40），`hookSpecificOutput.permissionDecision`(allow/deny/ask) 覆盖之（125-126），`hookEventName` 不匹配则丢弃事件域字段（122-124）。

② **claude-code 桥**：configPath 进程级一次性读取，失败仅告警不注册（index.ts:100-115）；接受 settings `{hooks:…}` 或裸事件表（config.ts:82-84）；7 事件全集（config.ts:11-19）；matcher 纯 `[A-Za-z0-9_|]+` 按管道字面量、否则正则（matcher.ts:18,61-64），UserPromptSubmit/Stop 丢弃 matcher（config.ts:109-111），非法正则抛 SyntaxError 整体拒配置（112-113）；`${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PROJECT_DIR}` 替换（57-62），env 默认导出会话工作区（index.ts:149-150）；stdin 带尾换行（168）；`updatedInput`/`systemMessage` 只记+warn（174-179）。

③ **codex 桥**：5 事件子集（config.ts:11）；`async:true` 跳过（67）、`timeout/timeoutSec` 别名（70-72）；无替换无 env；matcher 恒为正则（index.ts:129-130）；stdin 无尾换行（145）；exit 0 纯文本 stdout 升格为 additionalContext（149-155）；**仅阻断决定生效**：PreToolUse 只认 deny 不认 ask（223-229），PreStep 只 reject（197-211）。

④ **执行与超时**：`runHook` 走 `ctx.shell`（凭据清洗/进程组取消/超时，runner.ts:1-7）；默认 600 000ms（runner.ts:20），per-hook `timeoutSec` 覆盖（74）；多 hook 结果按 deny>ask>allow 取最严合并，stop 粘滞，context 按序累积（merge.ts:35-99）。审计：`hook/invoked`+`hook/result` 由 handlerId 配对、必须落在 open turn 内（events.ts:1-7,75-104），stderrSummary 截断默认 500 字符（events.ts:53,64-68），decision 缺省回落 pass/stop（events.ts:99）；invariant 插件强制 turn 包裹与配对（invariant.ts:36-58）。

⑤ **失败语义**：基础设施故障（workdir 不可用/缺 shell）转成无退出码的 outcome，绝不 throw（runner.ts:96-105）；无决定则 `next()` 委托下游（index.ts:240-242）；SessionStart 失败 catch 后 warn（209-211）——全链 fail-open。

## 接口与参数要点

- 桥配置：`configPath`(必填)、`pluginRoot?`、`projectDir?`、`defaultTimeoutMs`(默认 600000)、`stderrSummaryMaxChars`(默认 500)（claude index.ts:44-77）；codex 换 `model`(默认'')（codex index.ts:43-64）。
- 载荷字段全表：`session_id/cwd/hook_event_name`；CC：`transcript_path('')`、`prompt`、`tool_name/tool_input/tool_use_id/tool_response`、`source`、`stop_hook_active`、`agent_id/agent_type`；Codex：`transcript_path(null)/model/permission_mode/turn_id/last_assistant_message`。
- 退出码：0=继续（可带 JSON 表态 continue/decision/permissionDecision/additionalContext/updatedInput）；2=阻断（stderr→模型）；其他/无=非阻断错误。

## 边界与坑

- SessionStart/SubagentStart|Stop 为 emit 型 detached 运行，无 open turn 故**不写 hook/* 对**，只把 additionalContext 注入（claude index.ts:119,202-214,280-294；events.ts:3-5）。
- `continue:false`(merged.stop) 只记不停——缺 run 级 halt 机制（两桥 TODO，claude index.ts:188；codex 171）；Stop 阻断用 `agent.steer` 强续，循环护栏 TODO 且 `stop_hook_active` 恒 false（codex 254-269）。
- `transcript_path` 恒空/null（持久接缝缺口，claude index.ts:324-325）；`SUBAGENT_TYPE` 硬编码 'general-purpose'，specific matcher 永不命中（297-303）。
- 与 guard/审批分工：桥只产出 PreToolDecision——deny 直拦、ask 交由工具层统一审批流（claude index.ts:240-242）；Codex 忽略 ask。配置级却 fail-closed：非法正则拒整份配置。

## 对 duo 的启示

1. **事件面扩展优先级**：DSH 两桥共 7/5 事件但全走同一 `runPoint` 骨架，增量成本低；duo 现仅 PreToolUse/PostToolUse，最划算的下一个是 **UserPromptSubmit**（可 reject 烂 prompt）与 **Stop**（steer 强续，但需先补 stop_hook_active 防死循环）。SessionStart 是 DSH 唯一无审计的 detached 点，duo 若加需自定审计策略。
2. **载荷字段补齐**：duo 的 presenter_id/DUO_HOME 相当于方言专有 env，方向与 DSH 的 `CLAUDE_PROJECT_DIR` env 一致；可补 `session_id`、`tool_use_id`（回调关联合并结果用），`transcript_path` DSH 自己也留空，duo 可同样留空不阻塞。
3. **Codex 桥差异处理**：若 duo 复用 CC 格式，需记得 Codex 分支的「仅 deny 生效、无 ask、stdin 无尾换行、tool_input={command}、纯文本 stdout 当 context」五处分歧；exit 2=stderr 阻断两方言一致（codec.ts:66-69），与 duo 现状吻合。审计词汇（hook/invoked+result 成对、turn 包裹、stderr 截 500）可直接借用。
