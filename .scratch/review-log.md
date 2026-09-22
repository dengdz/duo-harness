# 审查总结（review-log）

持续维护的审查经验账——`duo-code-review` 每次审查收口时在此追加一节；下次审查开工前先读全文，把已知模式纳入两轮重点核对。与 `.scratch/bug-log.md` 分工：bug 个案归 bug-log，跨审查的模式与优化建议归这里。

## 已知模式（初始种子，随审查累积增删）

- **并发与生命周期**：订阅/回调句柄必须在 `finally` 释放；回调链 onNext/onComplete/onError 恰好一次且串行；await 期间取消、迟到回调要被标志挡住——先识别本仓库并发惯用模式（线程模型、串行化点、取消标志）再逐项核对。
- **SLF4J 陷阱**：尾随 Throwable 参数不填充占位符，必须显式 `toString()`。
- **能力与消费方匹配**：只有一个内部调用方的公开方法质疑其可见性；通用服务上长出消费方专用行为也是泄漏。
- **范围与必要性**：新抽象/状态机/选项/兼容路径必须映射到当前生产消费方，挑战投机泛型与无关功能。
- **包组织与目录分层**：按功能域分包，禁 `controller`/`service`/`util`/`impl` 大筐、根包不放类、新包同 diff 带 `package-info.java`；包内越过约 10 个类按功能边界拆子包（细则见 duo-project-structure）。
- **配置默认值**：每个默认值要有当前消费方证据或先例，没有就显式决策或推迟。
- **测试强度**：断言要在预期回归上失败、验证外部状态（事件顺序、流接收顺序）而非复述实现；mock 必须回放真实组件的异步语义——同步 mock 让超时与竞态逻辑永不生效。
- **文档同步**：配置项/默认值/异常行为/公开 API 变更同 diff 更新 README 与 docs；Javadoc `@throws` 与实现精确一致；消除已知限制时同步删除 `docs/limitations.md` 对应条目；与 ADR 相抵触是设计讨论，要在报告中显式提出。
- **中文注释**：新注释泄漏推理过程（审查编号残留、"本次修复"、变更叙述）按 duo-trim-cot-leakage 判定清理。
- **OCR 覆盖盲区**：open-code-review 的名单默认不含 `src/test/**` 与文档——测试与文档的核对结论由双轴轮与修复轮覆盖，报告覆盖率按 `git diff --stat` 全集口径标注。

## 审查记录

### 2026-09-22 · M23 工单 10 审查（0.18.0 分支，HEAD 工作树：迭代上限感知）

- **范围与轮次**：双轴（Standards + Spec 并行子代理）→ 修复 8 项 → OCR（委托模式，1 主代码文件行级）→ 修复 1 项 → 测试收口。两轮齐全（硬判据）。
- **计数**：双轴 Standards 2 P2 + 6 P3 + Spec 2 缺口（CHANGELOG/验收件）；OCR 1（双 Javadoc 叠放）；新增测试 4 用例（提醒时机恰一次/不落会话/达限回归/治理必达+单轮边界）。
- **模式化问题（本次新识别）**：
  1. **改方法签名时旧 Javadoc 叠放**——python/脚本改法新增注释块而旧单行注释未删，双层叠放仅末块生效；改方法头先删旧注释再写新块。
  2. **「半消除」型 limitations 销账**——能力半边落地时整行删除会丢失后续线索，改写为「部分销账 + 已落地半边 + 残留半边去向」三段式（工单 08 全消除删除线、工单 10 部分销账两形态并存）。
  3. **提醒类功能的注入位置要先答「治理会不会吞」**——治理 compaction 折叠历史，注入在治理前会被折叠吞掉；凡「必达注入」类语义，追加位置必须在治理管线之后并有用例锁定。
- **收口**：全量 test BUILD SUCCESS（agent 187）；两轮齐全（双轴 ✓ + OCR ✓）；CHANGELOG/limitations/工单记账。

### 2026-09-22 · M23 工单 09 审查（0.18.0 分支，HEAD 工作树：技能热加载）

- **范围与轮次**：双轴（Standards + Spec 并行子代理）→ 修复 10 项 → OCR（委托模式，2 主代码文件行级）→ 修复 1 项 → 测试收口。两轮齐全（硬判据）。
- **计数**：双轴 Standards 2 P2 + 9 P3 + Spec 2 缺口（CHANGELOG/limitations 销账）；OCR 1（技能全删残留陈旧清单——medium，已修）；新增测试 5 用例。
- **模式化问题（本次新识别，供后续重点核对）**：
  1. **WatchService 双固定坑（验收实测升级）**——key 复位必须 try/finally 全路径覆盖（异常轮不复位即该根静默失聪）；**目录删除时 Poller 直接 cancel key 且不投递事件不唤醒 take，防重集合（Set）残留失效 key 后重建目录永久失聪**——防重表用 Map<Path,WatchKey> 注册前校验 isValid，失效摘除重注册；「删除重建后热加载失效」回归用例必须覆盖。
  2. **长生命周期阻塞循环禁用虚拟线程**——PollingWatchService.take() 内部 synchronized 段在并发高载下 pin carrier 致虚拟线程饿死（watch 全量并发下系统性失聪实证）；改平台守护线程后全量稳定。生产隐患非仅测试问题。
  2b. **macOS PollingWatchService 多目录吞 MODIFY**——单目录 repro 正常、多目录注册下 MODIFY 稳定丢失（CREATE/DELETE 正常）；不能依赖事件完整性，watch 循环加心跳窗口（poll 超时也重扫，无变更零成本短路）把可靠性从「事件必达」降级为「事件加速 + 心跳保证」。诊断探针必须用合法目标形态（probe.md ≠ SKILL.md 造成过「未生效」假象）。
  3. **裸 -pl 假绿（mvn -am 教训第 4 犯加重）**——裸 -pl 依赖解析失败 0.1s 退出，连跑"全绿"实为未跑；mvn 命令必须带 -am 且跑完核对 "Tests run" 行真实出现。
  4. **环境时序敏感断言与感知解耦**——OS 事件感知时序是环境属性（macOS 轮询粒度负载下波动秒级到三十秒级），测试断言「机制正确」用强制收敛承载，「感知必达」留给人工验收兜底；但 watch 线程饿死这类生产隐患必须根治而非解耦。
- **收口**：全量 test BUILD SUCCESS（agent 182）；两轮齐全（双轴 ✓ + OCR ✓）；CHANGELOG/limitations/工单记账。

### 2026-09-22 · M23 工单 08 审查（0.18.0 分支，HEAD 工作树：.gitignore 忽略判定器）

- **范围与轮次**：双轴（Standards + Spec 并行子代理）→ 修复 12 项 → OCR（委托模式，6 主代码文件行级）→ 修复 1 项 → 测试收口。**新硬判据下两轮齐全，首张无跳步工单。**
- **计数**：双轴 Standards 4（P1 CHANGELOG + P2 limitations 销账 + P2 git 语义三处 + 死测试方法）+ Spec 2 偏差/缺口（接线措辞、验收件与 CHANGELOG 缺）；OCR 新增 1（字符类反斜杠翻倍破坏 \] 转义——已修）；新增测试 13 用例（判定器矩阵 12 + 同口径集成 1）。
- **模式化问题（本次新识别）**：
  1. **批量插入用例会吞相邻注解**——以「方法签名行」为 anchor 的 python 替换把前一个 @Test 挤给了新方法，旧用例静默变死方法（测试计数还 +1 互相掩护）；插入用例后 grep「void 方法名」必带 @Test 前行。
  2. **宿主正则语义 ≠ git wildmatch 语义的字符类陷阱**——Java 字符类 `&&` 是交集、git 是字面集合；翻译型判定器逐形态对拍（三形态 **、转义尾空格、字符类）要建对照用例矩阵，不能靠"编译通过"背书。
  3. **"三消费点共用同一实例"的字面失真**——同根语义等价（同 workspace root 同规则）≠ 字面同实例；注释写"同口径"比写"同实例"经得起接线变化。
- **收口**：全量 test BUILD SUCCESS（tools 210 含判定器矩阵 12）；两轮齐全（双轴 ✓ + OCR ✓）；CHANGELOG/limitations/工具目录/工单记账。

### 2026-09-22 · M23 工单 07 审查（0.18.0 分支，HEAD 工作树：headless --json）

- **范围与轮次**：双轴（Standards + Spec 并行子代理）→ 修复 9 项 → OCR（委托模式，7 主代码文件行级，**补跑**——本轮曾跳步，按新收口硬判据补齐）→ 修复 1 项（javadoc 变更叙述泄漏）→ 测试收口。
- **计数**：Standards 3 P2 + 8 P3；Spec 4 偏差/缺口（step 相位与 usage、降级链中间级、PipelineTimeout、记账缺）；新增测试 8 用例（帧序/退出码/禁交互含审批路径/恢复不重放/bounding 含负例/参数/yml 预过滤）。
- **模式化问题（本次新识别）**：
  1. **新呈现位装配漏"呈现位同款清单"**——CLI/Web 都挂的 PipelineTimeout、AuditingAnswerer 在 headless 首版全缺；新增呈现位时按"超时兜底/审计留痕/信号语义/退出码"四件清单逐项核对，不能只搬交互循环。
  2. **对外契约词汇计数与列举不一致**（工单"七类"实列 8 词）——对外发布的事件词汇表要"列举即权威"，计数口径从工单沿袭时会带病入库；契约注释写明"以列举为准"。
  3. **mock LLM 两轮脚本要回放真实消费语义**——次轮读 request 末条消息（工具结果回填）再作答，直答脚本测不出 tool_result 配对与帧序。
- **OCR 轮计数**：名单 7（覆盖率 100% 主代码口径）；新增 2（修 1：javadoc 泄漏 / 记档 1：openSession 非 Locked 异常路径无 error 帧——低概率且契约不破）。
- **收口**：全量 BUILD SUCCESS（example 22）；两轮齐全（双轴 ✓ + OCR ✓）；CHANGELOG/工单/运行Demo 记账。

### 2026-09-22 · M23 工单 06 验收实测修正（后台任务按呈现位归属过滤 + dbg 残留清理）

- **验收实测两问题**：①CLI 触发的后台任务出现在 Web **新会话**状态面（statusJson 全量吐 `registry.all()`，完成通知 listener 也是全量路由——同根：呈现位无归属概念）；②运行日志带 `[dbg-*]` System.err 调试残留 5 处（dbg-reg/notify/prompt/loop）。
- **修复口径**：归属复用 presenterId（M19「谁发起谁作答」既有载体，零新概念）——`BackgroundTask.owner` 贯穿 `FsBashTool.execute(exec.presenterId())` → `registry.start` 三参；三处呈现位（CLI 提示符 hint、Web 状态面、双侧完成通知 listener）统一按「本位或 null」过滤，null（子代理/直调）双面可见防静默失踪。dbg 残留全清。
- **模式化问题（本次新识别）**：
  1. **进程级注册表的可见化必须先问归属**——"全局 registry + 全量渲染"在单呈现位下无感、双呈现位装配下必然串显；新增可见化位时（提示符/状态面/通知）把"按发起方过滤"列入设计检查项，与 M19 OCR #22「先到先得实例上挂单绑定会串位」同族。
  2. **调试打印残留入库再犯**（工单 01「异常日志」同类，间隔 5 张工单）——`[dbg-*]` 前缀是为排障加的，实现完忘删；提交前 `grep -rn "System.err.println\|\[dbg" <改动文件>` 应入 duo-workflow 提交前核对清单。
- **收口**：全量 test BUILD SUCCESS（web 77 / cli 36 / agent 176）；CHANGELOG 补工单 06 条目（含归属过滤）。

### 2026-09-22 · M23 工单 06 审查（0.18.0 分支，HEAD 工作树：后台任务可见化）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 c752f89）→ 修复 → 全仓 test 收口。
- **计数**：双轴轻量（本工单体量小）；修复 3 项（statusJson 后台区块字段缺失补齐、CliPlugin 提示行空任务零噪声、注册表缺席部署零感）。
- **模式化问题**：可见化工单（读注册表渲染）的实现风险集中在**数据收敛**（终态后无幽灵条目）与**缺席零感**（注册表/服务不在场时部署形态不劣化）——测试各锁一条。
- **收口**：全量 BUILD SUCCESS；报告并入 `.scratch/m23-cli-experience/issues/06-*.md`。

### 2026-09-22 · M23 工单 05 审查（0.18.0 分支，HEAD 工作树：bash 输出三层与 spill）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 efe0ee6）→ 修复 → 全仓 test 收口。
- **计数**：双轴 Standards 5（硬违规 2：writer 生命周期 NPE / CHANGELOG+工具目录文档）+ Spec 3（后台缺 finish 回读缺尾为最重）；修复 10 项（含口径、命名、括号等小项）。
- **模式化问题（本次新识别）**：
  1. **「懒创建 + 有界写入」的 writer 生命周期三态（未开/开着/已关）必须显式建模**——finish 置 closed 停写，迟到 accept 只计丢弃不碰 writer；否则持流孙进程的迟到写入 NPE 杀读线程（正是本工单要修的「静默丢失」）。
  2. **回读指引行的位置就是它的生命**——带尾窗裁剪的输出通道里，指引/路径行拼中部会被尾窗裁掉；置尾。
  3. **失败态与满额态要分开文案**——「超帽丢弃」与「落盘失败回读不可用」混用一个标记，用户按超帽理解会去读不存在的文件。
- **收口**：全量 BUILD SUCCESS；报告并入 `.scratch/m23-cli-experience/issues/05-*.md`。

### 2026-09-22 · M23 工单 04 审查（0.18.0 分支，HEAD 工作树：bash run_in_background 与 task 面）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 8a148da）→ 修复 → 全仓 test 收口（OCR 并入双轴行级——本期两坑在 TDD 中实测暴露，双轴覆盖行级；与工单 03 跳步教训的区别：本轮双轴 prompt 明确列行级重点清单，后续保持）。
- **计数**：双轴 Standards 8（高优 1：通知路由 TOCTOU）+ Spec 2（通知竞态窗口/集成测试缺失）；修复 8 项；新增用例 12（Registry 5 / FsBash 3 / Cli 3 / Web 1）。
- **模式化问题（本次新识别）**：
  1. **开轮的 busy 占位判定必须在调用方 CAS，执行方法信任占位**——通知回调 CAS 占位后 startTurn 内部再 CAS 必然失败（占位已 true）丢通知；「判定与执行分离」两处各拿一半就双杀。
  2. **注册表的监听器要异常隔离 + 关闭先摘**——shutdown 杀树触发的通知会路由进拆树中的呈现位（死 SSE/死会话）；对齐 Context.emit 的隔离约定。
  3. **终态 first-wins 的状态机要防回写覆盖**——terminate 先置 KILLED 后杀树，监视线程 waitFor 正常返回的 EXITED 会覆盖 KILLED；状态迁移单向（RUNNING→终态不可逆写）。
  4. **新工具必须同步工具目录文档**——ToolCatalogTest 防漂移闸门抓到 task-output/task-stop 未入 docs/05-参考/工具目录.md（闸门有效性的正面样本）。
- **收口**：全量 BUILD SUCCESS（tools 190 / agent 176 / web 74 / cli 34 / session 30 / example 14）；报告并入 `.scratch/m23-cli-experience/issues/04-*.md`。

### 2026-09-22 · M23 工单 03 审查（0.18.0 分支，HEAD 工作树：审批小队列）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 f1ae65b）→ 修复 → 全仓 test 收口（OCR 轮由实现中实测发现替代——/stop 吞应答与 NIO 炸两坑在测试先行暴露）。
- **计数**：双轴 Standards 6（硬违规 2：CHANGELOG/视觉证据补录）+ Spec 3 疑点；修复 6 项（Esc/FIFO 对齐、pendingAsks 删除、CHANGELOG、测试复位真验证、格式、NIO 标志两处）。
- **模式化问题（本次新识别）**：
  1. **「唤醒即清」纪律要覆盖交互链路**——InterruptedException 的 catch 重设标志在 ask 路径上会炸审计事件落盘（approval/decided 的 NIO 写）；工具链路有 commitToolCall 清扫，交互链路无——两域的 catch都要不重设（布尔承载判定）。
  2. **应答闸门会吞命令**——行缓冲闸门把一切输入行当应答，/stop 被吞成 deny 回答挡死中断入口；闸门路由要给斜杠命令留逃逸口。
  3. **前端选择器索引与服务端队列语义要对齐**——Esc 点最新卡 vs complete 最旧的错位只在多卡时显现，但排队化引入的场景恰是它——前端动作与服务端语义同一端为准（FIFO 最旧）。
- **收口**：tools 183 / agent 176 / web 73 / cli 31 / session 30 全绿；报告并入 `.scratch/m23-cli-experience/issues/03-*.md`。

### 2026-09-22 · M23 工单 02 审查（0.18.0 分支，HEAD 工作树：暂停——协作式中断与恢复）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 3eaa2ba）→ 修复 → OCR（委托模式，9 代码文件行级）→ 修复 → 四模块 test 收口。
- **计数**：双轴 Standards 7（硬违规 2：CHANGELOG/事件表缺行）+ Spec 4 疑点（1 真窗口）；OCR 新增 1（微竞态复位序）；修复 6 项（armed 误判窗口、完成路径残留标志缺口、CHANGELOG、事件表、命名、静默降级提示）。
- **模式化问题（本次新识别）**：
  1. **中断唤醒后的线程标志残留会炸 NIO**——interrupt 只作唤醒用，唤醒点后立即 Thread.interrupted() 清除、判定走布尔；否则 FileChannel.write 抛 ClosedByInterruptException 杀死收口（三测试同根失败的真实根因）。
  2. **虚拟线程池的「等待方被打断 ≠ 任务被打断」**——future.get 抛 InterruptedException 后必须对触发枚 self-cancel(true)，try-with-resources 的 ExecutorService.close() 会傻等池任务自然超时。
  3. **SIGINT 拦截以 System.console() 门控**——真实终端才拦、测试/管道/headless 保留默认终止；headless 的 SIGINT=130 退出码契约天然成立（工单 07 直接受益）。
  4. **再按闸（armed）置位必须以「中断被接受」为前提**——与 busy 标志的生命周期不同步时（收口复位与 busy 清除间的窗口）会误杀新 turn 的第一次按键。
- **视觉验证先例**：browser-use 全链路（按钮出现→点击→中断卡→复位→续接）+ 隔离 DUO_HOME 起真实装配（避开用户活会话锁与固定端口）。
- **收口**：agent 176 / web 71 / cli 29 / session 30 全绿；报告并入 `.scratch/m23-cli-experience/issues/02-*.md`。

### 2026-09-21 · M23 工单 01 审查（0.18.0 分支，HEAD 工作树：事件驱动主循环与两级收件箱）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 5857def）→ 修复 → OCR（委托模式，workspace 5 代码文件全覆盖 + 2 测试文件由双轴轮覆盖）→ 修复 → cli+agent+web 三模块 test 收口。
- **计数**：双轴 Standards 5（硬竞态 1）+ Spec 2（时序缺陷 2，creep 3 项判定为合理伴生）；OCR 行级无新增 High/Critical（Low 2：应答闸门残留行边界、参数堆 Data Clumps，均记档不修）；修复 5 项（EOF 后审批挂死、turn 线程登记窗口、RuntimeException 静默死亡、单写者 javadoc 对账、idle 唤醒归属注记）。
- **模式化问题（本次新识别）**：
  1. **「呈现行 ≠ 执行时点」——工具叙述行（[调工具]）在执行完成后才打印，不能当 busy 期投递节奏标记**：测试锚点要选执行前已产出的信号（流式文本块、审批卡）。M22 探测的 ZCode「工具卡先呈现后执行」形态在 duo 是反的。
  2. **共用 stdin 的多读者拆分必须走显式闸门**——事件驱动读者线程与 ConsoleAnswerer 抢同一输入流，审批应答会静默丢失；闸门还要处理「EOF/stop 后才发起的审批」（closed 标志 fail-closed，仅补终止行不够）。
  3. **同步 REPL 改异步后的测试夹具要「节奏化」**——脚本化一次性输入在事件驱动下全部命中 busy 语义；管道 + 供给线程（默认等 idle 提示符计数、可按标记 busy 期投递）是可复用形态。
- **收口**：cli 27 / agent 172 / web 69 全绿；报告并入 `.scratch/m23-cli-experience/issues/01-事件驱动主循环与两级收件箱.md`。

### 2026-09-21 · M21 里程碑审查（0.16.0 分支，74c859e...6ac7077 + 两轮修复）

- **范围与轮次**：双轴（Standards + Spec 并行子代理）→ 修复 → OCR（委托模式，56 代码文件全覆盖）→ 修复 → 全仓 test 收口。
- **计数**：双轴 Standards 9（阻断 1）+ Spec 8（缺失/偏差 4、creep 3、疑似错误 2）；OCR 新增 2（均修）；新增/强化回归 8 处；两轮处置 17 项（修 14 / 接受记档 3）。
- **模式化问题（本次新识别）**：
  1. **"工单 05 接线真实配置"类跨工单承诺必须有对账**——read_image 闸门自工单 03 起写死 `() -> false`，工单 05 的接线承诺未兑现，静默失效直到用户实测抓到。多工单接力时，"占位待接线"要在工单状态文件与 limitations 双挂账。
  2. **投递/导出这类"请求物变体"路径，内存驻留要按形态惰性**——files 模式成功后仍预编码 base64 就是常驻放大（MessageImage 改为 base64/fileId 二选一即根除）。
  3. **记录型（record）紧凑构造器不能调实例访问器做校验**（字段未赋值恒默认值）——校验就地名内联。
  4. **文本标记行当协议（"附件已入库: <id>"）会被模型侧文本复读放大成伪造引用**——解析端必须降级（丢部件保请求），提取逻辑单一事实来源。
  5. **带外改文件（IDE/终端）没有事件**——索引/缓存类功能要配"未命中同步重建"兜底，不能只挂事件钩子（@file 补全验收实测缺口）。
- **沿袭核对**：M19 模式①（POSIX 释放陷阱）本次在"新读路径"上再度命中并根除（Session.heldByThisProcess 探针 + 索引跳过 + OS 级 tryLock 断言）——该模式升级为"任何读会话文件的代码，merge 前必查 HELD_LOCKS"。
- **收口**：`./mvnw test` 全仓 BUILD SUCCESS；报告 `.scratch/m21-input-sessions/reviews/2026-09-21-M21.md`。

---

### 2026-09-19/20 · M19 里程碑终审（0.14.0 分支，main..9664559）

- **范围与轮次**：双轴（Standards + Spec 子代理）→ 修复 → OCR（range 模式）→ 修复 → verify 收口。
- **计数**：双轴 P1×2 + P2×7 + Spec 偏差 5；OCR 27 条（修 21 / 文档化 3 / 不立案 3）；两轮合计新增回归测试 9 个。
- **模式化问题（本次新识别，供后续重点核对）**：
  1. **事件溯源 + 读侧恢复的组合要过 POSIX 意识**——新加的"读会话文件"静态助手必须先查 HELD_LOCKS：本进程持锁时关闭读取 fd 会释放属主锁（OCR #15，第 1 轮修复自引入、第 2 轮根除）。已有防线在 Session.load/isOccupied，但"新写一个只读扫描"时容易漏。
  2. **请求期变换改事件化时的"当轮 vs 重放"分裂**——凡是"事件落盘 + 返回值双出口"的路径，返回值必须与重放投影严格同构（OCR #12/P1-2 同源：治理压缩曾保留近端，投影重放却清空）。
  3. **双呈现位装配的"先到先得实例"上挂任何单一绑定（回调/供给）都会串位**—— bindSession 模式建立后要审有没有漏网的单绑定字段（OCR #22：回调漏了）。
  4. **命令互斥与探针分离**——busy 既是单飞闸又是探针时，命令执行期占闸会让探针误报；拆 agentRunning 后两义清晰（双轴 P2-4 + OCR #18/#3 同源）。
- **优化建议**：duo-code-review 流程中 OCR 的 `-B` 参数是背景文件不是 diff（误用会导致 selected 为空或超限拒绝）——范围审查用 `--from/--to`，输出禁过管道（直落文件）。已在本轮实践修正。
- **收口**：`./mvnw verify` BUILD SUCCESS；pom 全模块 0.14.0。

### 2026-09-20 · M20 里程碑审查（0.15.0 分支，ad240ba...2078b54 + 验收反馈修复）

- **范围与轮次**：双轴（Standards + Spec 并行子代理）→ 修复 → OCR（range 模式，10 文件合组超时后按 --exclude 拆三批重跑）→ 修复 → 全仓 test 收口。
- **计数**：双轴 Standards 5（硬违规 2）+ Spec 5；OCR 16 条（修 15 / 抑制 1）；新增回归测试 5 个 + 断言补强 3 处。
- **模式化问题（本次新识别）**：
  1. **管线 rootMessage 取最深 cause——工具侧精修错误消息必须"不链 cause"才能存活**：`RuntimeException(精修文本, e)` 的最深 cause 是原始异常，"[web_fetch 错误] 超时（Nms）"这类指引永远不达模型（OCR P1#1，medium）。两口径：要指引就不链；要原始细节就让它做最深。
  2. **"声明归声明者、裁决归策略"的审批纪律要先查声明侧**——给既有闸门加档位分支时，若无人声明 requiresApproval，闸门对未声明调用根本不介入（验收实测：read-only 档联网不 ask）。fix 采"档位感知声明"：静态恒声明会因"审批即独占"让默认档丢并行池。
  3. **"转换前预切"式限额对 head 重量级页面必丢正文**——字符上限切点落在 <head> 内则转换得到空正文。限额应后置于转换产物（OCR/验收双重确认）。
  4. **注释写"config 可省"前先核内核 bindConfig 契约**——声明 configType 的插件行缺 config 块即启动失败，"可省"只能指块内字段。
  5. **大 diff 一次 OCR 合组会超时**——10 个同包新文件合为一组 ~40 万 token 必超时；按 --exclude 拆三批（每批 3-4 文件）即可全覆盖。
- **优化建议**：UA 版本字面量随发布漂移已去版本化（duo-harness）；发布收口清单无需再列。
- **收口**：`./mvnw test` 全仓 BUILD SUCCESS；tools 174 / web 54。

### 2026-09-22 · M24 工单 01 审查（0.19.0 分支，HEAD 93c1e23 工作树：权限规则引擎地基）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 93c1e23）→ 修复 → OCR（委托模式，10 代码文件全覆盖；7 排除文件由双轴覆盖）→ 修复 → 全链 package 收口。
- **计数**：双轴 Standards 硬违规 1（CHANGELOG 缺账）+ 基线 judgement call 4 + Spec 缺失 2 / 正确性对照 8 项全过；OCR 新增 0 修、2 Low 记档；两轮处置 8 项（修 6 / 记档不修 2 中的美观项与微分配项）。
- **模式化问题（重现与强化）**：
  1. **M23 记档的「服务命名惯例」重现**——新服务名首版用连字符 `permission-rules`，视图接口（方法名即服务名）解析失败，恢复用例红；修正为 camelCase `permissionRules`。该经验条目升级为：**新服务发布前把 SERVICE_NAME 与视图方法名并排核对，两处逐字一致**（与 experience 2026-09-22 条互相引用）。
  2. **「过渡态注释指向工单记档」必须双处真落**——代码 javadoc 声称「过渡态记档于工单」但工单侧当时没有该记档（Spec 轴抓到），注释与工单的对账要像测试断言一样双向成立。
- **收口**：`mvn -pl duo-harness-example -am package` BUILD SUCCESS（774 用例 0 失败）；报告并入 `.scratch/m24-permission-security/issues/01-权限规则引擎地基.md`。

### 2026-09-22 · M24 工单 03 审查（0.19.0 分支，HEAD 1976acb 工作树：只读判定器与免审批接线）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 1976acb）→ 修复 → OCR（委托模式，6 代码文件全覆盖；7 排除文件由双轴覆盖）→ 修复 → 全链 package 收口。
- **计数**：双轴 Standards 硬违规 1（approval 行文档误导）+ 基线 judgement call 5 + Spec 阻断 1（路径归一逃逸口）+ 缺失 3；OCR 新增 0；两轮处置 12 项（修 11 / 待用户验收 1）。
- **模式化问题（本次新识别）**：
  1. **「命令词归一化」在免审白名单场景是逃逸口**——basename 剥路径让 `/tmp/evil/ls` 命中 `ls` 白名单即免审；安全白名单只认裸命令名，路径前缀一律疑罪从有（与权限规则的前缀匹配形成对照：规则匹配认词边界、白名单匹配拒路径）。
  2. **拆分 API 后的组合方法即死代码**——deny/allow 两段拆出后，原组合方法 verdict() 零生产调用，靠 Standards 轴 Speculative Generality 抓到；拆分时同步删组合入口。
  3. **装配接线测试的夹具双隔离**——trust root（.git 存在性）与规则文件来源都要指向临时目录，否则 surefire 的模块 cwd（无 .git）与用户真实 settings.json 会把确定性测试变成环境相关测试。
- **收口**：`mvn -pl duo-harness-example -am package` BUILD SUCCESS（796 用例 0 失败）；报告并入 `.scratch/m24-permission-security/issues/03-只读判定器与免审批接线.md`。

### 2026-09-22 · M24 工单 02 审查（0.19.0 分支，HEAD e6f3cb6 工作树：审批卡四值与规则生成）

- **范围与轮次**：双轴（Standards + Spec 并行子代理，基点 e6f3cb6）→ 修复 → OCR（委托模式，13 文件全覆盖；6 排除由双轴覆盖）→ 修复 → 全链 package 收口。
- **计数**：双轴 Standards 硬违规 1（事件表未同步）+ 轻微 1 + 基线 4 + Spec 阻断 2（Web 高危语义相悖、提问/计划 id 断链回归）；OCR 新增 0；两轮处置 9 项（修 6 / 记档 3）。
- **模式化问题（本次新识别）**：
  1. **「卡片 id 借 toolCallId 通道」只对审计事件成立**——ask_user/计划卡的渲染源是 tool/call 事件（toolCallId=LLM 调用 id），与 InteractionRequest.id 不同源；按 id 回填只适配渲染源与 id 同源的卡（approval/requested）。引入请求 id 时必须逐卡核对「渲染事件 → id 来源 → 回传」三点同源，否则回填静默 miss 挂到超时（比错卡更隐蔽的回归）。
  2. **双呈现位的键位语义必须服务端权威对齐**——同一 a/s 语义 CLI 是「非候选拒绝」，Web 若「非候选放行本次」就是安全缺口；候选态前端不可知时，服务端包装层归一是唯一权威裁决点。
  3. **扩展公共 record 字段时先 grep 全部规范构造器**——InteractionAnswer/InteractionRequest 加字段后，唯一遗漏点在测试夹具（不兼容类型编译期即暴露，无害）；生产行零遗漏。
- **收口**：`mvn -pl duo-harness-example -am package` BUILD SUCCESS（826 用例 0 失败）；报告并入 `.scratch/m24-permission-security/issues/02-审批卡四值与规则生成.md`。

### 2026-09-23 · M24 工单 08 审查（0.19.0 分支，三轴制首跑：provider 声明与 Anthropic 适配器）

- **范围与轮次**：三轴并行（Standards / Spec / 行级规则三子代理，基点 5971308 工作树）→ 修复 → 全链 package 收口（修复为小改，测试锁定，未触发复跑）。
- **计数**：Standards 硬违规 2（CHANGELOG 缺账、模块划分.md 两处失真）+ 基线 2；Spec 阻断 0 + 轻缺口 1；行级规则轴 medium 1（SSE error 帧静默吞）+ low 5；处置：修 6 / 记档 4。
- **三轴制首跑实证**：行级规则轴（新上下文子代理）产出 medium 1 + low 5——对比委托模式时代 OCR 轮连续 0~2 Low，「独立子代理扛行级」的改造立即见效；三轴并行总耗时与原两轮制相当，报告少一份。
- **模式化问题（本次新识别）**：
  1. **新增协议适配器时，「SSE 流内 error 帧」是最易漏的出口**——OpenAI 兼容面同构缺口仍在（记档），协议级错误经流内事件下发时若只认正常帧，半截 turn 会被静默返回；新适配器 checklist 加「流内 error 帧 → 异常上报」一项。
  2. **双协议适配器的骨架重复到阈值即提取**——85 行同构暂可接受，但工单 10 改思考映射触碰同一段时必须先提取公共件再改，避免双处漂移。
- **收口**：`mvn -pl duo-harness-example -am package` BUILD SUCCESS（848 用例 0 失败）；报告并入 `.scratch/m24-permission-security/issues/08-provider声明与Anthropic适配器.md`。
