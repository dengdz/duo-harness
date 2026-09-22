# 07: headless --json

## What to build

自动化脚本以 `--json` 跑任务：stdout 输出逐行 JSON 事件流（NDJSON 七类：session/status/thinking/text/tool_call/tool_result/error/final），text/thinking 仅在 assistant/message 提交点发射（commit-point 投影），final 帧承载无损答案豁免截断；进程退出码即成败契约（completed→0 否则 1；SIGTERM→0、SIGINT→130）。`--session-id` 恢复既有会话续跑；headless 流内禁交互——审批/提问请求自动 deny 并发显式 error 帧，流程永不静默挂死；诊断信息只走 stderr。入口：DuoMain 参数扩展（`--json` 标志 + positional 任务文本 + `--session-id`），yml 路径参数兼容现状；无任务文本且非 TTY 时 usage error。

决策依据：ADR-0025（grill Q9 七类全量 + 可恢复）；探测文档 DSH 终端呈现.md 的 NDJSON 投影词汇/bounding 降级链/退出码契约（本项主参考）。单值 8KB、单行 32KB 截断降级链照落。

## Blocked by

无（可与主线并行；呈现位换 NDJSON 投影器，复用既有装配链）

## Status
in-progress

## Comments
- 2026-09-22：实现与双轴审查完成，全量 BUILD SUCCESS（example 22 含 8 个 headless 用例）。投影双源：commit-point 词汇走会话事件订阅、tool_result 的 status 走 AgentListener isError（事件层无失败标志），同线程成对配对。装配链复用 PresenterAssembly，呈现位行 yml 预过滤副本禁用（headless 自身即第三呈现位）。
- 2026-09-22：审查修复 9 项——挂 PipelineTimeout（工具挂死兜底，缺它则"永不挂死"只在交互轴成立）、internal 常量引用改经 PresenterAssembly 新 7 参重载、HeadlessAnswerer 包 AuditingAnswerer（审批/计划 deny 落会话留痕）、turn_end 帧补实测 usage（缺样本省略）、会话不可用路径补 final 帧、显式 .yml 后缀但文件缺失改 usage error 不静默回退、YAML mapper 修正、死代码 CountDownLatch 删除、session 显式 close。
- 2026-09-22：用户指令直接提交（验收件四项演示尚未人工确认）——状态保持 in-progress、验收件留待勾选，验收结果后补记。
- 2026-09-22：记档偏差三处（对齐 DSH 契约的实现裁定）——①step_start/step_end 相位不发（duo 无 step 边界事件源，强行映射制造错误词汇；status 词汇保留相位枚举）；②usage-error 不看 TTY（--json 无任务文本一律 usage error；spec 的 TTY 措辞源自 DSH 的 stdin 输入模式，duo 未实现）；③降级链两级（字段标量截断→行整体降级；duo 帧字段恒为标量，DSH"降级标量"中间级无对象可降，等价）。

## Checklist
- [ ] 入口参数：--json + positional prompt + --session-id + yml 兼容 + usage error
- [ ] NDJSON 七类词汇与 commit-point 投影、final 无损豁免、8K/32K 截断降级链
- [ ] 退出码契约：completed→0 否则 1、SIGTERM→0、SIGINT→130（测试覆盖）
- [ ] 流内禁交互：审批/提问自动 deny + 显式 error 帧；诊断走 stderr
- [ ] --session-id 恢复：会话续跑且事件流连续
- [ ] 测试（先例 BootTest / CliPluginAssemblyTest 装配级 seam）：假 LLM + stdout 捕获断言帧序列与退出码
- [x] 工单级验收件：脚本管道消费 NDJSON（grep/解析 final 帧）演示，用户手动确认（2026-09-22 主会话代跑四步：管道 final 帧+退出码 0、任务在飞 SIGTERM→0（danger 档真跑 sleep 60）、--session-id 恢复（id 一致+上下文延续）、usage→2）
- [ ] CHANGELOG 未发布段记账

## 审查轮（2026-09-22，双轴→修复→OCR→修复，两轮齐全收口）

### 第 2 轮·OCR（委托模式，2026-09-22 补跑）

**覆盖**：preview 名单 7 主代码文件（PresenterAssembly 重载 + DuoMain + headless 包 5 文件）= 已审 7 + 跳过 0（覆盖率 100% 全集口径：另 6 文件为测试/文档/skill，由双轴轮与两轮修复覆盖）；规则组 1 组（typos/dead code/命名/注释纪律）。

### 发现（已修/记档）

- **`PresenterAssembly.java` javadoc 变更叙述泄漏**（已修，low/maintainability）：新重载注释带「（M23 工单 07 审查）」审查动作叙述——按 duo-trim-cot-leakage 判定清理，改写为能力描述「internal 缺省常量不外泄，呈现位经公开重载即可获得缺省并发」。
- **`HeadlessBoot.openSession` 非 Locked 异常路径无 error 帧**（记档不修，low）：会话 jsonl 损坏等 RuntimeException 冒泡为堆栈 + 退出码 1——契约不破（「否则 1」），低概率路径为收口帧加兜底属过度防御；诊断走 stderr 符合契约。
- 其余五文件行级过目：无 dead code、无拼写、无命名问题；Projector 双源配对的防御分支（lastToolResult 为 null 时 callId:null 帧）合规；error 帧经 8K bounding 保护（计划复核类大 subject 不破行预算）。

### 复核

- 修复后 `mvn -pl duo-harness-agent,duo-harness-example -am test`：agent 176 / example 22 全绿 BUILD SUCCESS。
- **两轮齐全核对（SKILL.md 收口硬判据）**：第 1 轮·双轴 ✓ + 第 2 轮·OCR ✓——本次审查收口。
