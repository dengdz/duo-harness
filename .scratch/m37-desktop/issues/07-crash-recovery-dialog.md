# 07: 崩溃恢复对话框——后端意外退出的兜底

## What to build

后端子进程在非退出流程中意外退出（崩溃/OOM/被杀）时的恢复面（ADR-0039 决策一「拉起并看护」、DSH 恢复对话框范式）：主窗不白屏干挂着——弹出原生恢复对话框，含 stderr 尾部诊断摘要（长度截断，防凭据泄漏进对话框）与两个动作：重启后端（重走 02 编排链：探测→spawn→锚点→重连原窗口）与退出应用（走 04 退出编排的收尾段，不再探活）。退出流程中主动 SIGTERM 的正常退出不触发本对话框（与 04 编排互斥）。

端到端可验：任务运行中手动 kill 后端进程 → 恢复对话框出现（诊断可见）→ 选重启 → 窗口恢复可用；选退出 → 应用干净退。

## Blocked by

02

## Status

in-progress（2026-10-04 实现完成、双轴审查修复毕、kill→检测→自动重启→原窗重连真机实证；**恢复对话框本体与「选退出」分支待用户手动验收**——smoke 自动化模式跳对话框，03/05 同口径）

## Checklist

- [x] 子进程非预期退出检测（与正常退出流程互斥的状态位：isUnexpectedExit 纯判定 + backendStopRequested/quitting 双标志）
- [x] 恢复对话框（诊断摘要截断 + 重启/退出两动作；smoke 模式自动重启替代——记档见 Comments）
- [x] 重启路径复用 02 编排链并重连原窗口（launchParams 复用 + findFreePort 换址 + 原窗 loadURL）
- [x] S3 缝扩展：崩溃检测真值表 3 例 + stderrTail 句柄断言（重启/退出分支为 Electron 接线，smoke 断言程序化覆盖——S3 口径内，见 Comments）
- [x] 真机验证：外部 kill → 检测 → 自动重启 → 原窗重连新址（smoke 断言全过）；对话框本体与退出分支待真人（与 03/05 并作 mac 验收件）

## Comments

- **互斥位形态（记档）**：`isUnexpectedExit({quitting, stopRequested})` 纯判定（quit-orchestration.ts，真值表 3 例锁定）——退出流程在途或已主动 stop 皆预期收口；`backendStopRequested` 置位点三处对称（confirmAndQuit/smoke 失败兜底/启动失败路径），restart 复位开新周期。审查后补：smoke 失败兜底置位对称化（成对路径并排核对口径）。
- **smoke 自动重启分流（记档）**：票面「弹出原生恢复对话框」无条件措辞——smoke 自动化模式跳对话框自动重启（模态框无人点挂死验收，M37-02 模式③），对话框本体留真人。restartBackend 失败两出口同样 smoke 分流（审查硬违规修复：stderr + exit(1)，不挂死）。
- **诊断双层截断（记档）**：句柄 `stderrTail()` 全量尾 8KB（滚动缓冲硬上限）；对话框 detail 再截 1200 字符（DSH 恢复对话框先例）；smoke 日志行 slice(-120)。
- **重启用新端口（相容记档）**：findFreePort 换址——「重连原窗口」指 BrowserWindow 复用（loadURL 不重建）；会话态在 ~/.duo 后端侧不因换址丢失；smoke 断言 `backend.url !== firstUrl` 反向钉死换址。
- **restart await 期竞态修复（审查实锤）**：重启 spawn 期间用户 Cmd-Q → 旧代码新句柄无人 stop 成孤儿占端口——await 返回后复查 quitting 则 stop 新句柄并返回。
- **验证**：tsc strict 绿；vitest 51 例全绿（isUnexpectedExit 3 + stderrTail 句柄断言 + 既有 48）；真机冒烟 15s 收口——外部 kill → `backend exited unexpectedly` 检测 → 自动重启换址（58135→62655）→ 原窗重连断言过 → 无 java 孤儿；机械自检两 grep 干净。

## 审查轮（2026-10-04 · 双轴（Standards/Spec）+ 行级/Java 轴豁免（零 Java diff，沿 03/05/06 口径））

**覆盖**：backend.ts/quit-orchestration.ts/main.ts diff + 两测试增量 = 100%

### 阻断（已修复）
- **[Standards 硬] restartBackend 两处 showErrorBox 无 smoke 门控**：重启失败（JVM 冷启超时等）在自动化链路弹模态框挂死验收——统一 `fail()` 出口：smoke 走 stderr+exit(1)、交互走对话框。

### 建议（处置：已修 4 / 记档 6）
- **[Standards P2·已修] 重启 await 期间 Cmd-Q 漏网**：spawn 后复查 quitting → stop 新句柄防孤儿。
- **[Standards P3·已修] monitorBackend 回调未包兜底**（事件回调 safely 纪律——smoke 下 catch+console.error 而非弹框）。
- **[Standards P3·已修] smoke 失败兜底置位对称**（backendStopRequested 三点一致）。
- **[Spec D1·已修] 对话框诊断 1200 字符截断**（8KB 全量留日志）。
- **[Standards P3·已修] 头注释序列补崩溃段**（双描述漂移家族第 4 犯位置）。
- **[Spec D3/Standards C10·记档] 重启/退出分支无直接单测**：Electron 接线层，smoke 断言程序化覆盖（S3 口径），票面措辞按实际形态理解。
- **[Standards C6·记档] 薄壳分工张力**：纯判定已抽 quit-orchestration，Electron 耦合段留壳——观察。
- **[Standards C7/C8/C9·记档] 收口三连重复 / !launchParams 防御不可达 / 固定 delay**——judgement 观察，08 期 smokeSequence 分段函数化一并整理。
- **[Spec D5·记档] 「选退出→干净退」与 04 真机项重叠**：对话框退出分支程序化不可达，真人验收援引 04 证据。

### 测试覆盖
- isUnexpectedExit 真值表 3 例（全组合）+ stderrTail 句柄断言；崩溃→重启→重连全链 smoke 断言（换址反向钉死）；缺口：对话框本体与退出分支（真人项，已记档）。

**测试收口**：审查修复后 tsc 绿、vitest 51 例绿、冒烟全断言过（exit 0）。双轴报告全文见本轮对话记录，本节为合并处置版。
