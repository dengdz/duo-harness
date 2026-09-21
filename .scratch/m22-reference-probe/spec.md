# M22 spec：参考项目深度探测与后续阶段规划（0.17.0）

> 状态：**已完成**（2026-09-21 全部 23 张工单关单；收口产出 ADR-0023；原立项记录：，沙箱出栈证据 = [DSH 沙箱与权限](../../docs/research/DSH/沙箱与权限.md) / [ZCode 沙箱与权限](../../docs/research/ZCode/沙箱与权限.md)）。事实基础：锚点 DSH `ddefc45f` / ZCode `872ad96`（2026-09-21 本地核验，github 离线未 pull）。本文是探测范围与产出的唯一依据；工单已拆出：同目录 `issues/` 23 张（01-22 探测、23 收尾；阻断边唯一——23 被 01-22 全体阻断）。背景决策：ADR-0016 原 M22「沙箱与权限深化」经用户裁定改道——沙箱出栈，M22 转为 **DSH/ZCode 全量机制探测 + 后续阶段规划**的文档里程碑；参考权重 **ZCode 为主、DSH 为辅**（duo-research 惯例已同步修订）。

## Problem Statement

duo 后续每一期的设计都缺同一类输入：参考项目的**机制级事实**——某个域内部怎么运转、设计取向是什么、哪些能直接转译到 duo 的 Java/虚拟线程形态。现有文档三层各有所缺：总览是全景（无机制纵深）、模块手册是接口参考（无流程与坑）、零散专册只覆盖沙箱权限/web 工具族/输入面。同时，沙箱出栈决议与 M23+ 版图必须建立在探测结论之上，1.0 的安全基线（「沙箱进退决议已做出」）才能闭合。

## Solution

把 M22 做成**纯文档里程碑**：对 DSH 与 ZCode 按功能项逐一探测（24 张探测+规划工单），每项落两家各一份**五段模板机制深析文档**；探测完成后产出 **ADR-0023：沙箱出栈与 M23+ 里程碑规划**，并完成 limitations / 术语表 / ADR-0016 / backlog 四处对账。此后每一期开发动手前，先读对应探测文档再 grill，不重扫源项目。

## User Stories

1. As a 后续里程碑设计者, I want 每个功能项有两家各一份机制深析文档, so that 设计 duо 对应能力时先读文档不重扫源码
2. As a 后续里程碑设计者, I want 文档固定五段（机制全貌/关键流程/接口参数要点/边界与坑/对 duo 的启示）, so that 任何一份文档的读法一致、可按段速查
3. As a M23（ZCode 对齐）设计者, I want T-06/T-08/T-09/T-10/T-12 的探测先行, so that 工具管线、子代理、编排、只读命令识别的设计有直接蓝本
4. As a M24（Web 呈现协议化）设计者, I want T-20 的交互流与卡片协议事实, so that 去魔法串改造有结构化字段的对照样本
5. As a duo 维护者, I want 每条结论带 `文件:行号` 锚点, so that 源项目演进后可增量复核而不是全盘重探
6. As a duo 维护者, I want 关键参数默认值全部抄自源码, so that 引用数值不必二次验证
7. As a duo 维护者, I want 探查中无法核实的点如实标注「未核实/不可锚定」, so that 文档不会用推测污染事实
8. As a 后续会话, I want 探测结论按项目分家落 `docs/research/<别名>/<域>/`, so that ZCode 为主参考后文档归属清晰、不出现合并目录
9. As a 里程碑收口者, I want 每张工单完成即在 research 索引登记 + CHANGELOG 记账, so that 进度可审计、收口对账零遗漏
10. As a 里程碑收口者, I want 中途任意时点停止时已落盘文档与登记全部有效, so that 探测可跨会话续作而不返工
11. As a 1.0 收口者, I want ADR-0023 记录沙箱出栈决议及其证据链, so that 1.0 判定标准中的「沙箱进退决议已做出」合法闭合
12. As a 1.0 收口者, I want ADR-0023 给出 M23+ 版图表（主题/范围/依据引用探测文档）, so that 后续每期立项有据可查
13. As a duo 维护者, I want limitations M12#1（bash 写范围不受 workspace 约束）改判为长期已知边界, so that 它不再指向任何里程碑、语义诚实
14. As a duo 维护者, I want 术语表中「沙箱（OS 级边界属 M22）」等措辞同步更新, so that 术语与决议一致
15. As a duo 维护者, I want `.gitignore` 语义与 Web 鉴权令牌+bind 移入 backlog 挂期, so that 被移出的功能债不丢失、由规划批排期
16. As a duo 维护者, I want ADR-0016 的 M22 行加出栈注记指向 ADR-0023, so that 新旧 ADR 互相可达、决策链不断
17. As a 首次接触参考项目的会话, I want 批 19 顺带把 DSH 插件化架构三件套刷新到现行锚点, so that 最老的过期文档被顺路治愈
18. As a T-12 的使用者, I want 只读命令识别（ZCode 单侧）深析且 DSH 无对应如实注记, so that M23 最核心蓝本不失真、也不虚构 DSH 对照
19. As a 部署者, I want 探测全程不提交 git, so that 所有产出先经人工审阅再入库（红线 1）
20. As a 后续会话, I want spec 记录批 0-4 已完成清单, so that 拆单与续作不重复已完成的探测

## Implementation Decisions

1. **里程碑性质**：纯文档里程碑，零功能代码改动；版本仍为 0.17.0，一里程碑一版本的纪律不变。
2. **工单结构**：T-03 至 T-26 共 24 张探测工单（T-03/T-04 已完成）+ T-27 收尾工单；每张探测工单 = 一个功能项 × 两家各一份文档；域目录与两家对应关系见下方批表。
3. **文档规范**：五段统一模板（① 机制全貌 ② 关键流程 ③ 接口与参数要点——不复读模块手册 ④ 边界与坑 ⑤ 对 duo 的启示——面向 Java/虚拟线程形态给转译建议）；路径 `docs/research/<别名>/<域目录>/<模块>.md`，文件名一律中文、与批表功能项同名（既有英文命名文件已全部改中文名，后续工单照此）；两家分家、不建合并目录；跨项目对照只出现在「对 duo 的启示」段或 ADR。
4. **准确性纪律**：锚点 `文件:行号` 必须逐条给；默认值抄源码；无法核实明示；结论只对锚点负责，github 恢复可达后 `pull --ff-only` 校验，锚点变动则增量补扫。
5. **执行纪律**：每张工单 = 派两家并行探查子代理 → 逐份落盘（报告回到即写盘，不攒）→ 索引登记 + CHANGELOG 记账 → 向用户汇报要点；执行节奏由用户控制（单张叫号或点名连跑）；任意中断已落盘产出有效。
6. **批表**（✅=已完成）：

| 工单 | 功能项 | DSH ↔ ZCode | 域目录 |
|---|---|---|---|
| T-03 ✅ | Agent 循环与状态机 | core/agent-loop+agent ↔ core/runtime+core/agent | `Agent循环与会话/` |
| T-04 ✅ | 会话事件模型与持久化 | core/session+persistence/jsonl ↔ 事件词表+sqlite-session-store | 同上 |
| T-05 | 投影、恢复与分页 | projection+title ↔ resume/hydrator+分页 | 同上 |
| T-06 | 工具注册与执行管线 | core/tools+guard ↔ core/tool | `工具系统/` |
| T-07 | 本机执行工具族 | fs/shell/subprocess/terminal ↔ exec+fs+node-pty | 同上 |
| T-08 | 子代理 | subagent 域 ↔ core/subagent | `子代理与编排/` |
| T-09 | 任务编排 workflow | workflow+ptc/code-runtime ↔ dynamic-workflow(+runtime) | 同上 |
| T-10 | 任务管理 | todo/goal/plan-mode/jobs/schedule ↔ todo/cron/off-peak/background | 同上 |
| T-11 | 审批与提问交互（交互流半边） | user-approval/user-questions ↔ permission-flow 审批执行+hook 竞速 | `交互与呈现/` |
| T-12 | 只读命令识别与规则（DSH 无对应注记） | —— ↔ bash-readonly-policy 族+前缀规则（单侧） | 同上 |
| T-13 | LLM 调用层 | llm/retry/meter/deepseek/pi-ai ↔ adapters/model+provider | `LLM与上下文/` |
| T-14 | 系统提示与上下文工程 | system-prompt+context 族 ↔ core/context+memory | 同上 |
| T-15 | 压缩治理 | compaction+spill ↔ core/compact | 同上 |
| T-16 | hooks | 协议+信任链（两家） | `扩展机制/` |
| T-17 | skills 与命令 | skill+commands ↔ skills+slash-command-surface | 同上 |
| T-18 | MCP | mcp-client+resources ↔ adapters/mcp | 同上 |
| T-19 | 插件与装配（顺带补扫 DSH 插件化三件套） | bundle/boot/preset ↔ plugins/marketplace+bootstrap | 同上 |
| T-20 | Web 呈现 | client/ui-* ↔ ui+web+v4 投影消费 | `呈现与UI/` |
| T-21 | 终端呈现 | cli/headless/acp ↔ tui/cli | 同上 |
| T-22 | 桌面端 | desktop/desktop-host ↔ desktop+host 进程 | 同上 |
| T-23 | 网络与远程工具族 | web/lsp/ssh/e2b/browser-use ↔ webfetch/websearch/http/mailbox | `网络与数据/` |
| T-24 | 存储配置与凭据 | storage/settings/credentials/workspace ↔ config+auth+LocalSetting | 同上 |
| T-25 | 会话检索与导出 | session-query+deliverables ↔ read-session-context+share | 同上 |
| T-26 | API/遥测/SDK 面 | api/host/sdk/acp/telemetry ↔ server/zcode-server-cli/telemetry/debug | 同上 |
| T-27 | 收尾：ADR-0023 + 四处对账 | 决议+版图表；limitations M12#1 改判、术语表口径、ADR-0016 注记、backlog 对账（`.gitignore`/Web 鉴权/工程化探测入账） | docs/adr/ 等 |

7. **git 纪律**：全程不提交（红线 1），改动累积工作区待用户确认；里程碑收口走 duo-workflow（CHANGELOG/limitations 同 diff、双轴审查、确认后提交）。

## Testing Decisions

本里程碑无自动化测试，验收=逐工单核对清单：

- 每张探测工单：两家文档存在于约定路径、五段齐全、锚点非空、索引登记两行、CHANGELOG 有记账行；
- 抽查口径：任取文档中 3 个锚点能在源仓库对上（行号±3 内）；任取 3 个默认值与源码一致；
- T-19：DSH 插件化架构三件套头部锚点更新为 ddefc45f 且索引状态改「当前」；
- T-27：ADR-0023 存在且被索引/ADR-0016 注记双向可达；limitations M12#1 无残留「M22」去向字样；backlog 三项入账；术语表无过期口径。

## Out of Scope

- 一切功能代码开发：glob/grep `.gitignore` 语义（M12#2）、Web 鉴权令牌+bind——随 T-27 移入 backlog 挂期；
- 工程化与分发探测（测试哲学/CI/打包/更新，原 B11 批）——backlog 记一行，不入本期；
- duo 自身任何行为变更（T-27 对账件除外）；
- 沙箱与权限域的重复探测（专册已覆盖，T-11/T-12 只探未覆盖半边）；
- 对照长文（对照结论只进「对 duo 的启示」段与 ADR）。

## Further Notes

- 参考权重裁定（ZCode 主、DSH 辅）已落 duo-research SKILL 与注册表；DSH 语言标注误标 Java 已核正为 TypeScript。
- 探测过程中若发现批表某项在两家均无实质对应物（如 T-12 之 DSH 侧），以「如实注记」代替虚构对照，必要时缩减为单侧文档。
- 里程碑收口后，后续每期启动的探测消费规则：直接读 `docs/research/<别名>/<域>/` 文档，锚点过期才增量补扫。
