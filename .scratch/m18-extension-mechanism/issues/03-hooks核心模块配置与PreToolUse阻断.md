# 03: hooks 核心——模块 + 配置 + PreToolUse 阻断端到端

**What to build:** 用户把含 PreToolUse 钩子的配置放进 `~/.duo/hooks.json`、boot yml 加一行 hooks 插件，钩子脚本以 exit 2 阻断匹配的工具调用，模型收到含 stderr 原因的错误结果——"从 Claude Code 拷一段配置就能拦危险操作"的最短完整链路。演示：钩子拦下一次工具调用，模型收到被拒原因。

**Blocked by:** None (can start immediately，与 01/02 并行)

**Status:** ready-for-agent

语义权威：ADR-0019 决策 1/2/3（本单做其 PreToolUse 子集）/5/6；新 Maven 模块 `duo-harness-hooks`。

- [ ] 新模块入 pom 与 README 模块表（同 diff）；插件 `inject()` 硬依赖 `ToolsService`；opt-in = boot yml 插件行——不装行零感知零开销；装行而配置缺失/为空 = 插件 ACTIVE 空转
- [ ] `~/.duo/hooks.json`（DuoHome 解析）顶层 `{"hooks": {...}}` 与 Claude Code/Codex 同形；未知键宽容忽略；解析失败 → hooks 插件 FAILED、点名文件与位置，不连坐 boot 树
- [ ] PreToolUse 钩子 exit 2 → 匹配调用被拒：工具结果为错误形态且含 stderr（模型可见）；与审批同段先到先决，deny 占先省掉审批交互；钩子只收不放（无"跳过审批放行"能力）
- [ ] matcher 本单先支持精确名与省略/`*` 全匹配（多选与正则 04 补全）；处理器仅 `"type": "command"`
- [ ] 钩子进程经 stdin 收到本单可定的最小载荷（hook_event_name、tool_name、tool_input、tool_use_id）与 `DUO_HOME` 环境变量
- [ ] M17 并发调度零改动：钩子在每次调用的管线内部执行，不参与分组判定与屏障，成对有序提交不受影响
- [ ] 测试：Boot 全链路端到端（`@TempDir` 注入 DUO_HOME + 真 hooks.json + `sh`/`exit` 类系统命令作钩子）+ ToolsService 管线缝假工具断言；格式解析（宽容跳过、坏 JSON 点名）在 hooks 模块内纯单元测试
