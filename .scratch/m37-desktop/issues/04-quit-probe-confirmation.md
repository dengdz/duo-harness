# 04: 退出编排与探活确认——防误杀长任务

## What to build

真退出流程（ADR-0039 决策一「退出前探活确认」、DSH quit-inspection 范式）：Cmd-Q / 应用菜单退出 / 托盘菜单退出统一走一条编排——先探活既有 `/api/status` 判 agent 活跃 → 活跃弹原生确认对话框（取消 = 回到常驻，应用不退；确认 = 继续）→ SIGTERM 子进程（后端 shutdown hook 级联 dispose、会话锁确定性释放已有）→ 壳退出；探活失败（后端已死/无响应）按可退处理不拦。关窗隐藏路径不走本编排（03 裁定），只有真退出才探活。

端到端可验：跑一个长任务 → 退出 → 被确认框拦下 → 取消回常驻；无任务时退出 → 干净退（后端进程树无残留）。

## Blocked by

03

## Status

in-progress（2026-10-04 实现完成、四轴审查修复毕、idle 直退路径真机实证；**长任务拦截路径待用户手动验收**——busy 真值依赖 LLM 在飞 send，无法自动化）

## Checklist

- [x] 退出编排三入口统一（Cmd-Q/应用菜单/托盘菜单；03 占位换真——before-quit 拦一次归一）
- [x] `/api/status` 探活 + 活跃确认对话框（取消回常驻；探活判据经用户裁定为 status 加 `turnActive` 字段——见 Comments 方案记档）
- [x] SIGTERM 子进程 + 退出完成路径；探活失败按可退处理
- [x] S3 缝扩展：退出编排状态机单测（活跃/不活跃/探活失败三分支 + 忙时征询，quit-orchestration 8 例）
- [x] 真机验证：idle 直退 + 进程树无残留（ps 实证 0）；**长任务拦截项待真人**（busy 路径 LLM 依赖，程序化不可达——见 Comments 记档）

## Comments

- **探活判据方案记档（票面预设不成立，用户已裁定）**：spec「探活用既有 /api/status」前提经实现面核实不成立——status 载荷无任何 turn 活跃字段。经 grill 逐题裁定（2026-10-04，对话在案）采「status 加 `turnActive`」方案：`ToolCallingAgent` 全局 AtomicLong 在飞 send 计数（跨 tab、跨 CLI/Web——CLI 跑任务时壳同样探得到）+ statusJson 暴露布尔（纯新增 1.x 兼容；壳侧缺字段按空闲 fail-open 可退）。**后端改动面四处**（spec/ADR 勘误注二已加）；CHANGELOG 未发布段 Added 记账。
- **退出编排形态（记档）**：before-quit 拦一次（preventDefault）→ `resolveQuit` 决策核（探活忙 → `showMessageBoxSync` 征询〔按钮「强制退出/取消」，cancelId=1〕→ 按答 quit/cancel）→ 置位 quitting 放行 close → SIGTERM → `exited` promise 3.5s 竞速（超时 SIGKILL 兜底——CliPlugin 死锁回归界 stop ≤3s，2s 会截断合法最慢收口，取 ZCode host ≥3.5s 先例）→ 重入 app.quit。探活窗口内二次退出有 `quitConfirming` 重入守卫（审查修复）。探活超时 2s、SIGKILL 宽限 3.5s 为工单期定值。
- **长任务拦截真人项记档**：busy=true 的端到端（真 turn 在飞 → 退出被拦 → 确认框出现）依赖真实 LLM 在飞 send，程序化不可达——Java 侧已用可阻塞假适配器锁计数真值（在飞 true/收口 false/抛穿归还三态，ToolCallingAgentTest 2 用例），TS 侧 parse 三态 + 决策四分支全锁；剩「真 turn → 真对话框」一跳留用户手动验收（与 03 托盘手势并作一次 mac 验收件）。
- **验证**：tsc strict 绿；vitest 38 例全绿（quit-orchestration 8 + backend 21 + window-control 10——含 buildStatusUrl/parseBusyStatus 三态/四决策分支/exited 即决）；Java web+agent 1016 例 0 失败（删旧报告重跑 exit 0，含 turnActive 线缆锁 + 计数三态新锁）；真机冒烟 10s 收口（idle 直退走真探活路径、java 残留 0）；机械自检两 grep 干净。

## 审查轮（2026-10-04 · 四轴）

**覆盖**：Java 3 文件（ToolCallingAgent/WebEndpoints/契约测试）+ desktop 4 文件（backend/quit-orchestration/main/测试）= 100%（行级轴 OCR 覆盖 2 Java 主代码全文通读；TS 归 Standards/Spec 轴）

### 阻断（已修复）
- **[Standards P1 红线 6/Spec] CHANGELOG 缺口**：turnActive 为 /api/status 用户可见新字段（1.0.1 auth 字段先例）——未发布段 Added 记账（已修）。
- **[Standards P2 硬违规] 新用例 finally 无条件 clearProperty(duo.home)**：违背 M37-01 立的「保存旧值按旧值形态恢复」机械核对项——改保存/恢复对称（已修）。
- **[Standards P2·顺带逮出 02 残留] S1 用例 oldHome 捕获在 setProperty 之后**：恢复形态失真（预置 `-Dduo.home` 永不被还原）——捕获上移至 set 之前（已修）。
- **[Standards P2] before-quit 探活窗口二次退出重入**：重复弹框/重复 stop——补 `quitConfirming` 重入守卫（已修）。

### 建议（处置：已修 4 / 记档 4）
- **[Java MAJOR COM-04·已修] 尾随注释上移**（文件主流为语句上方注释）。
- **[Java MAJOR UT-07·已修] turnActive 在飞/抛穿边界用例补齐**：可阻塞假适配器（latch）锁「在飞 true/收口 false」+ 抛穿锁「计数归还」——increment/finally 拆散即红。
- **[Java P2·已修] doSend 补一行 Javadoc + 类线程约定段补静态共享态声明**。
- **[Standards P3·已修] SIGKILL 宽限 2s→3.5s**（对齐死锁回归界，防截断合法最慢收口）；2000ms 裸值随语义定值并注释依据。
- **[行级 C10/Standards P3·记档] web 主代码下探 agent.internal 包**：HEAD 即有先例（WebPlugin/WebTabs 已引用），非本票引入——公开门面转发留后续架构议题。
- **[Standards P3·记档] fetchTurnActive 超时参单消费方**：保留形参（票面「无响应」操作化定值 2s），不内联。
- **[Standards P3·记档] 契约测试装配脚手架与 S1 重复约 20 行**：三用例同构，抽 helper 的churn 大于收益，观察。
- **[Standards P3·记档] smoke 失败路径 stop 无 SIGKILL 兜底**：验收路径求快退，SIGTERM 干净退有实证——不对称记档。

### 测试覆盖
- 决策四分支（空闲/忙+确认/忙+取消/失败不征询）+ parseBusy 三态（true/false/缺字段）+ 坏 JSON + buildStatusUrl + exited 即决——全部锁外部行为；turnActive 计数三态（空闲/在飞/抛穿）Java 侧新锁。缺口：busy 端到端真人项（已记档）。

**测试收口**：审查修复后 TS 38 例绿、Java web+agent 1016 例 0 失败（exit 0）、真机冒烟 idle 直退 java 残留 0。四轴报告全文见本轮对话记录，本节为合并处置版。
