# 03: hooks 核心——模块 + 配置 + PreToolUse 阻断端到端

**What to build:** 用户把含 PreToolUse 钩子的配置放进 `~/.duo/hooks.json`、boot yml 加一行 hooks 插件，钩子脚本以 exit 2 阻断匹配的工具调用，模型收到含 stderr 原因的错误结果——"从 Claude Code 拷一段配置就能拦危险操作"的最短完整链路。演示：钩子拦下一次工具调用，模型收到被拒原因。

**Blocked by:** None (can start immediately，与 01/02 并行)

**Status:** done（2026-09-18 用户真机验收通过：A 组 exit 2 拦截 + stderr 回给模型且模型如实汇报不绕行；B 组 fail-open 放行；C 组空转对照；17 例全绿）

语义权威：ADR-0019 决策 1/2/3（本单做其 PreToolUse 子集）/5/6；新 Maven 模块 `duo-harness-hooks`。

- [x] 新模块入 pom 与 README 模块表（同 diff）；插件 `inject()` 硬依赖 `ToolsService`；opt-in = boot yml 插件行——不装行零感知零开销；装行而配置缺失/为空 = 插件 ACTIVE 空转
- [x] `~/.duo/hooks.json`（DuoHome 解析）顶层 `{"hooks": {...}}` 与 Claude Code/Codex 同形；未知键宽容忽略；解析失败 → hooks 插件 FAILED、点名文件与位置，不连坐 boot 树
- [x] PreToolUse 钩子 exit 2 → 匹配调用被拒：工具结果为错误形态且含 stderr（模型可见）；与审批同段先到先决，deny 占先省掉审批交互；钩子只收不放（无"跳过审批放行"能力）
- [x] matcher 本单先支持精确名与省略/`*` 全匹配（多选与正则 04 补全）；处理器仅 `"type": "command"`
- [x] 钩子进程经 stdin 收到本单可定的最小载荷（hook_event_name、tool_name、tool_input、tool_use_id）与 `DUO_HOME` 环境变量
- [x] M17 并发调度零改动：钩子在每次调用的管线内部执行，不参与分组判定与屏障，成对有序提交不受影响
- [x] 测试：Boot 全链路端到端（`@TempDir` 注入 DUO_HOME + 真 hooks.json + `sh`/`exit` 类系统命令作钩子）+ ToolsService 管线缝假工具断言；格式解析（宽容跳过、坏 JSON 点名）在 hooks 模块内纯单元测试

## Comments

**实现摘要（2026-09-18）**

- 新模块 `duo-harness-hooks`（包 `dev.duo.harness.hooks`，四类）：`HooksPlugin`（插件行 opt-in；apply 读 DuoHome 根下 hooks.json，空配置空转；挂 `tools/pre-execute` waterfall 监听器——挂点即 `ctx.on`，tools 域零改动）；`HooksConfig`（解析：结构错误 PluginException 点名文件 → 插件 FAILED 不连坐；条目级问题跳过 + WARN；兼容 Claude Code matcher 组与 **Codex 扁平 command 条目**两形态——后者是解析器实测两家文档后加的兼容）；`HookMatcher`（03 档：absent/空白/`*` 全匹配 + 整串精确）；`HookRunner`（进程执行：args 在场 exec 直启 / 缺省 `sh -c`、stdin 喂 JSON 载荷、`DUO_HOME` 注入、stdout/stderr 虚拟线程收集防管道死锁、限时 `destroyForcibly`）。
- 监听器语义：命中钩子逐个执行，exit 2 → `exec.deny("被 PreToolUse 钩子阻断: <stderr>")` 且不调 next；fail-open（超时/启动失败/非零非 2 退出 → 放行 + WARN）；exit 0 的 stdout JSON 裁定留 04。

**偏离与裁定留痕**

- **`tool_use_id` 一期缺席**（对工单验收面的偏离，如实记录）：管线载荷 `ToolExecution` 不携带调用标识，提供它需动 `ToolsService.execute` 入口签名——与 ADR-0019 决策 6"tools 域零改动"冲突，两害相权取零改动；载荷一期为三字段（hook_event_name/tool_name/tool_input），`tool_use_id` 与其余字段（session_id 等）随 04 一并处理并把该缺口记入 05 的 backlog 对账（新增条目"钩子载荷 tool_use_id 管线透传"）。
- 钩子条目级 `timeout`（缺省 600s）在 HookRunner 一并实现：03 交付"无限等钩子"的管线不可接受（M17 管线超时不覆盖 pre-execute 段），语义验收留在 04。
- E2E 测试顺带验证了工单 01 的读取纪律：探针插件最初未声明 inject 读 tools 被正确点名拒绝（补声明后通过）。

**测试证据**

- hooks 模块 17 例全绿：`HooksConfigTest` 7（两家同形/未知键宽容/事件跳过点名/条目级跳过/结构错误点名/空文件）+ `HookRunnerTest` 5（stdin 载荷送达、exit 2 stderr、exec 直启、超时终止、启动失败）+ `HooksPluginEndToEndTest` 5（Boot 全链路真进程：exit 2 阻断匹配工具且 plain_tool 不受累、脚本缺失 fail-open、空配置空转、坏 JSON FAILED 点名不连坐、不支持事件跳过后照常）。
- 全量 `./mvnw package` 十一模块 BUILD SUCCESS。

**验收件（待手动验证）**

演示三场景（拦截 / fail-open / 对照）+ 预期对照表：

| # | 场景与动作 | 应出现 |
|---|---|---|
| A1 | 拦截版 hooks.json 启动 | WARN 行 `hooks 配置含暂不支持的事件（已跳过）: [SessionStart]`（宽容跳过），REPL 正常起来 |
| A2 | A 组输入"读一下 config.yml" | `[工具错误] 工具 "read" 执行被拒绝: 被 PreToolUse 钩子阻断: 验收钩子拦截`；模型改口说明无法读取（stderr 回给模型） |
| B1 | fail-open 版（钩子路径不存在）重启后同样提问 | 工具正常返回文件内容；WARN 行 `PreToolUse 钩子非零退出（非阻断，放行）: exit=127 …`（sh 找不到命令的 127 为非阻断面） |
| C1 | 对照组：删除 hooks.json 重启后同样提问 | 工具正常返回、无钩子相关日志（空配置空转） |

测试路径叙述行（套件级）：`HooksConfigTest —— hooks.json 解析：两家同形、宽容跳过、结构错误点名（7 用例）`；`HookRunnerTest —— 钩子进程执行：stdin 载荷、退出码/stderr、exec 直启、超时终止、启动失败（5 用例）`；`HooksPluginEndToEndTest —— hooks 端到端（真进程）：exit 2 阻断、fail-open、空转、坏配置 FAILED 点名、事件跳过（5 用例）`。
