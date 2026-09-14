# 03: DuoMain 与启动收敛

## What to build

通用启动器 `DuoMain`（example 模块）：`Boot.from(yml)` + 非守护等待（CountDownLatch）+ shutdown hook 级联 `root.dispose()`——纯 Web 部署的 JVM 存活与 Ctrl-C 的锁释放由它保证。`AgentReplMain` 瘦身为兼容壳（main 委托 DuoMain；run(in, out) 测试入口保留注入形态）；agent-demo.yml 启用 cli 行（双开验证：行序即回答者路由、交互工具查重、会话独占互斥）。

## Blocked by

02（AgentReplMain 瘦身依赖 REPL 已迁 CliPlugin）

## Status
in-progress

## Checklist
- [x] DuoMain：Boot.from（默认 agent-demo.yml）+ CountDownLatch 保活 + shutdown hook 级联 dispose
- [x] AgentReplMain 瘦身：main 委托 DuoMain；REPL/装配/会话管理代码移除；AgentReplMainTest 迁移与保留边界清晰
- [x] agent-demo.yml 启用 cli 行（行序决定回答者路由——文档标注）；example pom 增加 duo-harness-cli 依赖
- [x] 实测：双开启动后 CLI 与 Web 各持会话锁、互拒正确（/exit 后 CLI 锁释放、Web 不受影响）
- [x] 全量回归绿（Web 面零回归）

## Comments

- 实现（2026-09-15）：`DuoMain`（example）——Boot.from（默认 agent-demo.yml 资源，args[0] 可指定路径）+ CountDownLatch 非守护等待 + shutdown hook（dispose + countDown 分离，防 dispose 异常挂死 main）+ run 的 finally 兜底 dispose（回调失败/信号竞态都释放树与会话锁）；`ContextConsumer` 函数式接口允许受检异常（演示装配含文件 IO）。`AgentReplMain` 瘦身 296→44 行兼容壳：main 委托 DuoMain.run 并注入演示专属装配回调（MCP files 沙箱 + 演示提示片段——需运行时临时目录与 classpath，无法落 yml 行，经 ContextConsumer 注入保住"DuoMain 不含业务装配"边界）。agent-demo.yml 启用 cli 行（web 之后——行序即回答者路由，审批/提问由 Web 卡片优先呈现）。spec 的 run(in,out) 保留句按实现演进对账（测试入口由 CliPlugin 注入构造承担，超集）。
- 审查（委托 OCR + 双轴）修复：DuoMain.run 契约补全（@param/@throws/失败模式——hook dispose 抛错会挂死 main 的真缺陷已修）；spec 红线 3 对账（run(in,out) 措辞）；System.in stub（cli 行读 surefire stdin 会阻塞）。
- 验证：双开实测——启动后 Web 持 c267、CLI 新建 f6fa 各持锁；`/exit` 后 CLI 锁释放、Web 存活（200）；全量 292 用例绿；日志叙述齐（DuoMain/CLI 提示/会话行）。
- 待手动验证：用户确认后置 done（REPL 交互与 04 验收件合并验收）。