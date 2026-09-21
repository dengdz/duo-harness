# hooks（ZCode）

> 源锚点：`872ad960de7ec172591f7e1952f7849229f94521`（2026-09-21 本地核验）。M22 探测里程碑工单 12（T-16）产物，五段统一模板。接口参考见 [../模块手册.md](../模块手册.md)；PermissionRequest hook 竞速见 [../交互与呈现/审批与提问交互.md](../交互与呈现/审批与提问交互.md)。

## 机制全貌

ZCode hooks = 进程/命令回调执行器 + 7 事件面 + 项目级钩子信任链 + 工具管线挂点。`InMemoryHookRunner.run` 按 event+matcher 过滤注册项后逐个 dispatch（core/src/hooks/runner.ts:52-55），超时默认 60s（runner.ts:42）；`createConfiguredHookRunner` 把配置钩子（user/plugin/internal）与工作区快照钩子（project）合并，project 钩子插在同事件首个非 user 来源之前（configured-runner.ts:15-28、174-191）。事件 7 种：SessionStart/UserPromptSubmit/PreToolUse/PermissionRequest/PostToolUse/PostToolUseFailure/Stop（contracts/src/hooks/index.ts:7-15）。管线接入：PreToolUse 在入参归一化后、权限解析前（tool/executor/call-runner.ts:232→289），PostToolUse/PostToolUseFailure 在执行后（call-runner.ts:471、561）；PermissionRequest hook 与权限 broker 并发竞速（executor/permission-flow.ts:184-236）。来源：插件 `hooks/hooks.json` 与 manifest.hooks（adapters/src/plugins/hook-sources.ts:8、42-99），项目级来自 zcode.json/.zcode/config.json 快照（contracts hooks/workspace-hook-trust.ts:249）。hooks 默认关闭（opt-in），默认 60s/32KB 输出上限（contracts hooks/index.ts:425-430）。

## 关键流程

① **匹配与执行**：matcher 空/`*` 全匹配；纯 `[a-zA-Z0-9_|]` 按管道切分枚举，否则按正则（hooks/output.ts:98-112）；matchValue=工具名+兼容别名（hook-flow.ts:42-43）。超时 `hook.timeoutMs ?? 60000`，AbortController+timer，超时抛 recoverable 的 ToolTimeout 错误（runner.ts:334-379、runner-helpers.ts:66-70）；async 钩子后台执行，输出不反向影响本 turn（runner.ts:125-153）。

② **信任链**：`evaluateWorkspaceHookEntry` 按 policy deny→blocked_policy；store corrupt→blocked_untrusted；allow_trusted_only 无记录→blocked_policy；有持久记录→trusted_persistent；被 revoke→revoked；同槽位（event/路径/索引同但 digest 变）→stale_digest；否则 pending_trust（workspace-hook-trust-evaluation.ts:15-63）。七态中 revoked/stale_digest/pending_trust 均归 admissionClass="pending" 不可运行（workspace-hook-trust.ts:87-130）。审查窗 10min（workspace-hook-trust.ts:4），超时 settle 为 timed_out、reason=workspace_hooks_interaction_timeout（workspace-hook-review-flow.ts:227-240）。**授权不缓存**：预扫描仅决定 skipLifecycle 与 clientVisibleHookCount，每个 hook 实际 dispatch 前重跑 admission，revoke/策略收紧/store reload 立即生效（runner.ts:61-90）；admission gate 自身异常 fail-closed（runner-helpers.ts:23-33）。

③ **输出表态**：stdout JSON 经 `processHookOutput`（output.ts:11-65）：`continue:false`→blockRequested+preventContinuation（限 PreToolUse/PermissionRequest/UserPromptSubmit，output.ts:154-160）；`decision:approve/block`→permissionBehavior；Stop 的 `continue:true`→stopShouldContinue。hookSpecificOutput：PreToolUse 支持 permissionDecision allow/ask/deny+updatedInput（output.ts:120-128）；PermissionRequest 支持 decision allow{permissionUpdates,updatedInput}/deny{interrupt,message}（contracts hooks/index.ts:150-161、208-246）。updatedInput 改写后重新 normalize+validateInput（call-runner.ts:268-287），PermissionRequest modify 后重查权限（permission-flow.ts:240-266）；多 hook 聚合 deny>ask>allow（output.ts:145-152）。

④ **失败与遥测**：HookOutcome 五态 success/blocked/failed/cancelled/timed_out（contracts hooks/index.ts:19-25），错误→outcome 映射（runner-helpers.ts:78-83）；每 hook 发 HookRunStarted/Completed/Blocked/Failed 事件，阻断原因进持久化 payload（runner.ts:113、174-210、215-232）；skipLifecycle 钩子剔除后再算 hookCount。

## 接口与参数要点

- HookEventName 全表 7 值（contracts hooks/index.ts:7-15）；HookOutcome 5 值；HookSourceKind user/plugin/project/internal（:29-34）。
- 配置：`{enabled, timeoutMs, maxOutputBytes, events:{事件:[{matcher, hooks:[{type:"command",command,async?,shell?,timeout/timeoutMs,statusMessage}|{type:"process",command,args}]}]}}`（contracts hooks/index.ts:303-341、407-423）。
- stdin 载荷为 camelCase 主契约+Claude 兼容别名（hook_event_name/tool_input/transcript_path 临时文件等，configured-runner-input.ts:16-63）。
- 超时：全局默认 60000ms，hook 级 timeoutMs 覆盖；信任审查 10000×6ms；进程钩子 timeout 单位秒、timeoutMs 毫秒并存。

## 边界与坑

- 授权决定不得缓存，须在执行边界按 security revision 重验（runner.ts:87-90）；PermissionRequest hook 链基础设施故障只令其退赛，**不得替用户拒绝**，确认窗继续等 broker（permission-flow.ts:186-198）。
- PreToolUse 的整体 allow 不能抹掉 alwaysAsk 确认（hook-flow.ts:203-208）。
- skipLifecycle 钩子曾致 hookCount 虚高（runner.ts:61-63 修复注释）；hook 与 broker 曾串行导致确认窗永久死亡，现竞速（permission-flow.ts:179-183）。
- 项目钩子排序保证先于插件/内部执行（configured-runner.ts:174-191）。

## 对 duo 的启示

duo 现状：duo-harness-hooks 仅 PreToolUse/PostToolUse（HooksConfig.java:40-41），exit 2 否决+stderr 回模型（HooksPlugin.java:29），fail-open。对照三条：

1. **项目级钩子信任链**——ZCode 对 project 来源强制 pending_trust→trusted_persistent 状态机+10min 审查窗+声明 digest（stale_digest 触发重审），duo 若支持项目级 hooks.json 应补同款防供应链（对应 backlog「项目级 hooks 配置」项）。
2. **PermissionRequest hook 结构化应答**（allow{permissionUpdates,updatedInput}/deny/modify+改写后权限重查）与 broker 竞速、故障退赛，比 exit code 表态更适合 duo 未来审批桥接。
3. **PostToolUseFailure 事件**（携 error.type/isInterrupt）补失败路径可观测；授权逐 dispatch 重验可作 duo 防 TOCTOU 借鉴。
