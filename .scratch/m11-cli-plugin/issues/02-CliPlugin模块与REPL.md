# 02: CliPlugin 模块与 REPL

## What to build

新模块 `duo-harness-cli`：`CliPlugin`（Boot 插件，对称 WebPlugin）——apply 装配完整执行链（经共享装配器；会话续接/新建取独占锁，占用即 FAILED 点名会话）+ 虚拟线程 REPL 循环（/new、/exit、/技能名直调、审批 y/n、ask 选项/自由输入、思考模型叙述，交互沿 AgentReplMain 既有形态全量搬迁）。`/exit` 或 EOF = **idle**：循环退出、会话锁释放、回答者摘除，插件保持挂载。`ConsoleAnswerer` 从 example 上移本模块。

## Blocked by

01（使用共享装配器）

## Status
in-progress

## Checklist
- [x] 模块骨架 duo-harness-cli（pom 依赖 core/tools/session/agent/llm）+ CliPlugin（默认构造 System.in/out；注入构造 in/out/sessionsDir 测试 seam）
- [x] ConsoleAnswerer 从 example 上移（含其测试迁移）
- [x] /技能名直调解析随 REPL 迁移（AgentReplMain.resolveSkillInvocation → CliPlugin，测试同步）
- [x] REPL seam 测试：脚本输入驱动——会话事件序列、/new 落新会话、审批 y/n 呈现、/技能名注入、/exit 与 EOF 后会话文件可重新加锁（idle）
- [x] 占用 seam 测试：最新会话被同进程实例持有时明确提示并改开新会话（订正：原误写 FAILED，与 spec US6/M10-03 既定语义矛盾，实现按后者）
- [x] 装配 seam 测试：纯 CLI yml Boot 后交互工具在册（对齐 WebPluginAssemblyTest 先例；依赖 ~/.duo/config.yml 先例披露）

## Comments

- 实现（2026-09-15）：新模块 duo-harness-cli（pom + package-info）；CliPlugin 对称 WebPlugin——共享装配器装配、会话独占续接/新建（被占提示+新开）、ConsoleAnswerer+审计桥、虚拟线程 REPL（/new、/plan、/技能名直调、未知命令提示、异常终止、AgentListener 三回调形态零漂移）；**idle 语义**（/exit 或 EOF → 循环退出+锁释放+回答者摘除+插件保持挂载，统一 goIdle 收尾）；stop() 关输入流打断+中断；LLM 未配置 FAILED 且消息含 yml 示例。ConsoleAnswerer 与 resolveSkillInvocation 自 example git mv（AgentReplMain 的副本暂留，03 工单瘦身时删——审查确认的临时双份）。
- 审查（委托 OCR + 双轴）修复：① 文档同步（模块划分表加 cli 行+依赖图、CHANGELOG 0.6.0 加 CLI 插件条目）；② SkillInvocationTest 变更叙述泄漏措辞；③ CliPlugin 线程契约补声明、sessionsDir 单点化、指导摘除统一 disposeGuidance；④ spec/工单占用语义措辞订正（原 FAILED 与 US6/M10-03 矛盾——**ADR-0011 决策 2 的"占用即 FAILED 点名——复用 M10-03 语义"自相矛盾（FAILED ≠ M10-03 的 CLI 行为），按括注意图读作复用 M10-03 语义=提示+新开，ADR 本体按"落卷不可改"不动，矛盾留痕**）；⑤ 补测试缺口——EOF 转 idle、REPL 级审批 y/n（mock LLM 发起需审批工具调用→同输入流 y 放行）、idle 后回答者摘除断言（ask 立即 fail-closed）。
- 留档：/技能名的 REPL 级（经 SkillsPlugin 真实发现根）未自动化——SkillsPlugin 发现根固定（cwd/DuoHome），fixture 注入需演进其配置，解析逻辑已有静态用例锁定，REPL 接线为一行调用；归 backlog 视需求。
- 验证：cli 模块 14/14（REPL 5：基本轮+事件、/new 换绑、占用提示、EOF idle+回答者摘除、审批 y；装配 1；技能 1；ConsoleAnswerer 7）；全量 292 用例绿；docs:build 绿。
- 待手动验证：用户确认后置 done（REPL 交互可与 03 的双开演示合并验收）。