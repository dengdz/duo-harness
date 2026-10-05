# 02: 壳工程骨架 + 拉起后端开窗——最小可用桌面

## What to build

桌面壳的 tracer bullet：仓库根新建 `desktop/` Node/TS 工程（Electron + TypeScript + vitest + electron-builder 配置骨架；依赖引入已获 ADR-0039 Q2 红线 4 同意）。壳 main 进程完成最小编排链：JDK 21 探测（login shell 环境探测，mac GUI 启动不继承 shell PATH——DSH 教训照抄；缺失/版本不符弹指引对话框）→ 试绑 0 端口取空闲值 → 以 `DUO_WEB_PORT` 环境变量 spawn 后端 fat-jar（**stdin 用 pipe 保持打开、从不写入**）→ 逐行扫 stdout 认 `duo:web-ready` 锚点 → 主窗直连 `http://127.0.0.1:<port>` 加载现有 Web UI。启动超时（60s 量级）或后端异常退出 → 失败对话框（含 stderr 尾部摘要）。

端到端可验：命令行启动壳 → 弹出窗口即是一个完整可用的 duo（会话/审批/工具全功能，因为就是现有 Web UI）。**实现首日必验**：空 stdin 下 cli 行 REPL 阻塞等待而非 EOF 退出（M27 机制认知：cli apply 即 REPL 主循环）——实测留记录于本票 Comments，若 EOF 退出则调整 stdin 策略并记档。

## Blocked by

01

## Status

done（2026-10-05 用户验收通过——npm start 起壳全能力，stdin pipe 策略与首日实测在案）

## Checklist

- [x] `desktop/` 工程链就绪（package.json/tsconfig/vitest/electron-builder 骨架；npm install 可跑）
- [x] JDK 21 探测 + 缺失指引对话框（login shell 环境探测）
- [x] 端口探测 + spawn 编排（env 注入 + stdin pipe 策略）+ 锚点解析 + 窗口加载
- [x] 启动超时/异常失败对话框（stderr 尾部诊断截断）
- [x] 空 stdin REPL 行为首日实测记录（Comments 留档，策略依实测定稿）
- [x] S3 壳编排缝 vitest：mock 子进程锁 spawn 参数/锚点解析/超时路径
- [x] 真机冒烟：命令行起壳得到可用 duo（截图留档）

## Comments

- **首日实测（stdin pipe 策略，结论=成立）**：探针 spawn fat-jar（`DUO_WEB_PORT=18999`，stdin pipe 不写不关）——锚点 0.3s 到达（端口覆盖端到端生效实证）、锚点后 15s 进程存活（**空 stdin 下 REPL 阻塞等待而非 EOF 退出**，cli 行 `你>` 提示符正常在 stdout）、无 CPU 空转。策略定稿：`stdio:['pipe','pipe','pipe']`，stdin 句柄保留不写。
- **首日实测逮出后端死锁（BUG-20261004-01，超出票面的第三处后端改动）**：SIGTERM 后 40s+ 不退——jstack 定位 `CliPlugin.stop()` 的 `in.close()` 与阻塞 readLine 读者同锁（JDK BufferedReader InternalLock）互等死锁；终端 Ctrl-C 钩子链同病。修复=stop 不关 reader（JVM halt 兜底）+ 回归锁 CliPluginStopDeadlockTest；修复后 SIGTERM 0.5s 干净退（code=143 复验）。spec/ADR「改动面两处」已加勘误注（三处）；CHANGELOG 未发布段 Fixed 记账；细节见 bug-log。
- **JDK 探测实测坑（已修）**：`java -version` 退出 0 但版本串走 stderr——execFileSync 成功路径只回 stdout，版本解析恒 null 致全候选误判「未命中」（首跑冒烟实锤）；换 spawnSync 聚合双流后通过。login shell 探测（`$SHELL -ilc 'command -v java'`）本机验证 760ms 返回。
- **失败对话框的 smoke 分流（记档）**：交互模式走 `dialog.showErrorBox`（含 stderr 尾部）；`--smoke` 自动化验收模式退化为 stderr + exit(1)——无人点模态框会挂死验收（首跑实测挂死教训），代码注释在案。超时定值 60_000ms（票面「60s 量级」落钉）。
- **真机冒烟证据**：`npm run smoke` 全链路 10s 收口——java=21 → jar 定位（glob 取最新 duo-harness-1.2.0.jar）→ 端口 60227 → 锚点 URL → 窗口加载 → capturePage 截屏 [.scratch/m37-desktop/smoke-window.png](../smoke-window.png)（Web UI 完整：会话侧栏/对话区/暗色状态面板，红线 5 视觉验证过）；退出无 java 孤儿进程（ps 实证 0）。
- **验证**：S3 vitest 19 例全绿（锚点/spawn 参数/env 注入/超时/ENOENT/截断/stop）；tsc strict 绿；cli 模块 42 例（含新死锁回归锁）；全仓回归 1169 例 0 失败 0 错误（删旧报告重跑，无管道 exit 0）；机械自检两 grep 干净。

## 审查轮（2026-10-04 · 四轴）

**覆盖**：工作树全集（desktop/ 壳工程 7 文件 + CliPlugin.java + CliPluginStopDeadlockTest.java + 工单票）= 已审 100%（行级轴 OCR 规则组仅覆盖 Java 主代码 CliPlugin 1/1；TS 归 Standards/Spec 轴；测试文件归 Standards/Spec/Java 轴）

### 阻断（已修复）
- **[Standards 硬/行级 F2 F3/Java CRITICAL COM-07 多轴共识] CliPlugin 两处陈旧注释**：类 Javadoc（:75-76「关输入流打断阻塞读」）与 replLoop catch 注释（:785）仍陈述已删除的 stop 关流行为——均改写为现在时（不碰 reader / 流读错误含外部关流罕见态）。
- **[Standards 硬·红线 6/Spec A3] CHANGELOG 缺口**：CliPlugin 挂死修复用户可见 → 未发布段 Fixed 记账（BUG-20261004-01）；桌面壳骨架记账归工单 08 收口时点（主审裁定，沿工单 01 记账口径）。

### 建议（处置：已修 3 / 记档 5）
- **[Standards P3/Spec A1 A2 A4·已修] 工单票滞后**：本节即补——首日实测/冒烟证据/审查轮全量入账；截图挂本票。
- **[Standards P3·已修] javaPathCandidates 壳候选重复**（SHELL=/bin/zsh 时探测两次）→ 同名去重（Set），顺带删除无消费方的 source 字段（Speculative Generality）。
- **[Standards P3/Java P2·已修] CoT 泄漏轻剪**：stop 注释与测试 Javadoc 的「jstack 坐实」过程叙述剪除，「实测 40s+」证据保留（duo-trim-cot-leakage 保留规则）。
- **[行级 F1 Medium·记档] 存活 JVM 内 cli 行 dispose 边界**：修复前该路径同样死锁（close 互等），修复后为 REPL 持锁驻留——两态均不支持，stop 注释 + bug-log 双侧记档「重启换装」，不扩行为。
- **[Spec B1·记档] CliPlugin 修复升格记档**：票面外第三处后端改动——spec/ADR 勘误注 + CHANGELOG + bug-log 四处落位（见上）。
- **[Spec C2·记档] smoke 失败退化 stderr+exit(1)**：偏离票面「失败对话框」无条件措辞，代码注释在案。
- **[Java P2/行级 C8·记档] 回归锁 sleep(300) 竞态窗口**：失效方向为假通过（漏报非误报），latch 同步成本大于收益，不修。
- **[Standards P3·记档] BackendHandle.child 暂无消费方**：工单 04（退出编排）/07（崩溃恢复）声明消费方在案，保留。

### 测试覆盖
- backend.ts 六簇全锁（锚点/spawn 参数与 env/进程先退/ENOENT/超时 kill/stop SIGTERM + 截断上限）；CliPlugin 死锁回归锁（读者阻塞持锁 → stop 3s 内完成）；修复后 TS 19 例 + cli 42 例 + 全仓 1169 例三面实证。

**测试收口**：注释/去重修复后 TS 19 例绿、cli 模块 42 例绿（exit 0）。四轴报告全文见本轮对话记录，本节为合并处置版。
