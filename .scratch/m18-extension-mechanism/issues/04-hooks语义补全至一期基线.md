# 04: hooks 语义补全至一期基线

**What to build:** "从 Claude Code 粘贴完整 hooks 配置即用"到此成立——PostToolUse 审计、JSON 裁定、matcher 全集、条目级超时、双进程形态、完整载荷、fail-open 全语义。运维者获得"第三方脚本故障不拖垮 agent"的可预期行为。

**Blocked by:** 03（hooks 核心——模块 + 配置 + PreToolUse 阻断端到端）

**Status:** ready-for-agent

语义权威：ADR-0019 决策 3/4 全量；spec 实现决策"hooks 配置与保真基线"节。

- [ ] PostToolUse 钩子：exit 2 → 结果改写为错误形态回给模型（stderr/reason 呈现），不假装撤销副作用；allow/静默 = 结果原样
- [ ] exit 0 stdout JSON 双通道：`permissionDecision` deny/allow + reason（reason 优先呈现；allow 不带改写等价放行）；其余非零退出 = 非阻断 + WARN 日志
- [ ] matcher 两档全集：纯字母数字 `_ - | ,` = 精确名/多选；含其他字符 = 正则；`*`/省略 = 全匹配
- [ ] 条目级 `timeout`（秒）缺省 600s：超时 = 取消进程、丢弃输出、放行 + WARN
- [ ] fail-open 全语义：钩子进程崩溃、路径不存在起不来 → 放行 + WARN 点名（钩子不是执法边界）
- [ ] 进程双形态：`args` 数组在场 = exec 直启；缺省 = `sh -c` shell 形态
- [ ] stdin 载荷补全一期七字段：session_id、transcript_path、cwd、hook_event_name、tool_name、tool_input、tool_use_id（+ `DUO_HOME`）
- [ ] 不支持的事件/处理器类型跳过 + WARN 点名（粘贴两家完整配置不炸启动）
- [ ] 测试覆盖上述全语义（管线缝假工具 + 端到端真进程）；真机验收件：用户粘贴一份真实 Claude Code 钩子配置亲手验证（duo-acceptance 惯例，隔离端口与 DUO_HOME）
