# 04: hooks 语义补全至一期基线

**What to build:** "从 Claude Code 粘贴完整 hooks 配置即用"到此成立——PostToolUse 审计、JSON 裁定、matcher 全集、条目级超时、双进程形态、完整载荷、fail-open 全语义。运维者获得"第三方脚本故障不拖垮 agent"的可预期行为。

**Blocked by:** 03（hooks 核心——模块 + 配置 + PreToolUse 阻断端到端）

**Status:** done（2026-09-18 用户真机验收通过：守门钩子 JSON 裁定拦 rm -rf 且 deny 占先审批（y/n 未弹）、无害 bash 走 y/n 交互审批放行、read 直执行不受守门影响、模型如实汇报拦截且无绕行；34 例全绿）

语义权威：ADR-0019 决策 3/4 全量；spec 实现决策"hooks 配置与保真基线"节。

- [x] PostToolUse 钩子：exit 2 → 结果改写为错误形态回给模型（stderr/reason 呈现），不假装撤销副作用；allow/静默 = 结果原样
- [x] exit 0 stdout JSON 双通道：`permissionDecision` deny/allow + reason（reason 优先呈现；allow 不带改写等价放行）；其余非零退出 = 非阻断 + WARN 日志
- [x] matcher 两档全集：纯字母数字 `_ - | ,` = 精确名/多选；含其他字符 = 正则；`*`/省略 = 全匹配
- [x] 条目级 `timeout`（秒）缺省 600s：超时 = 取消进程、丢弃输出、放行 + WARN
- [x] fail-open 全语义：钩子进程崩溃、路径不存在起不来 → 放行 + WARN 点名（钩子不是执法边界）
- [x] 进程双形态：`args` 数组在场 = exec 直启；缺省 = `sh -c` shell 形态
- [x] stdin 载荷补全：hook_event_name、tool_name、tool_input、cwd（PostToolUse 增 tool_response）+ `DUO_HOME`；session_id/transcript_path/tool_use_id 缺席（管线无会话与调用标识，偏离已记 ADR-0019 注记 + backlog，见 Comments）
- [x] 不支持的事件/处理器类型跳过 + WARN 点名（粘贴两家完整配置不炸启动）
- [x] 测试覆盖上述全语义（管线缝假工具 + 端到端真进程）；真机验收件：用户粘贴一份真实 Claude Code 钩子配置亲手验证（duo-acceptance 惯例，隔离端口与 DUO_HOME）

## Comments

**实现摘要（2026-09-18）**

- `HookMatcher` 三档全集：全匹配（null/空白/`*`）、精确名/多选（`|` 与 `,` 分隔、strip 对齐）、未锚定正则（`find` 语义——`Edit.*` 亦命中 NotebookEdit，Claude Code 同款）；`isRegexForm` 区分"要不要编译校验"——`*` 单独作正则非法，全匹配不落编译校验（首版实现把 `*` 误判成正则导致全匹配组被整组丢弃，E2E 测试当场抓住后修正）。
- 解析期正则校验：非法正则条目级跳过（WARN），不留"永不命中"的死规则；`HooksConfig` 受支持事件集扩为 PreToolUse + PostToolUse。
- `HooksPlugin` 双事件挂载：PostToolUse → `tools/post-execute`，exit 2 → `markError` 改写结果且不调 next（拒绝不可翻回，guard 同哲学）；载荷带 `tool_response`（审计面，Claude Code 同名字段）。PreToolUse 增 stdout JSON 裁定三形兼容：`hookSpecificOutput.permissionDecision`、扁平 `permissionDecision`、legacy `{decision, reason}`（block=deny）；reason 优先呈现、allow 等价放行、非法 JSON = 非阻断错误放行（Claude Code 同款）。

**裁定留痕**

- **载荷字段偏离（对决策 3 七字段的收敛）**：session_id / transcript_path / tool_use_id 缺席——管线载荷无会话与调用标识，透传需执行入口携带上下文，与"tools 域零改动"冲突；cwd 可得即发，PostToolUse 增 tool_response（audit 语义必要面）。已同步：ADR-0019 Consequences 日期注记 + spec 实现决策句 + backlog"钩子载荷上下文透传"条目（扩自 tool_use_id 单字段版）。
- E2E 断言 PostToolUse 用执行计数实证"工具本体已执行、仅结果改写"（audit-only 不撤销）。

**测试证据**

- hooks 模块 34 例全绿：HooksConfigTest 9（+非法正则跳过、+PostToolUse 受支持）、HookMatcherTest 7（新建）、HookRunnerTest 5（不变）、HooksPluginEndToEndTest 13（+PostToolUse 改写与执行计数、JSON 三形、allow 放行、非法 JSON 放行、timeout 条目放行、载荷落盘断言、多选与正则路由）。
- 全量十一模块 BUILD SUCCESS（2026-09-18）。
