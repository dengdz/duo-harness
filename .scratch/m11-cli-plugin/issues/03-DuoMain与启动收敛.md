# 03: DuoMain 与启动收敛

## What to build

通用启动器 `DuoMain`（example 模块）：`Boot.from(yml)` + 非守护等待（CountDownLatch）+ shutdown hook 级联 `root.dispose()`——纯 Web 部署的 JVM 存活与 Ctrl-C 的锁释放由它保证。`AgentReplMain` 瘦身为兼容壳（main 委托 DuoMain；run(in, out) 测试入口保留注入形态）；agent-demo.yml 启用 cli 行（双开验证：行序即回答者路由、交互工具查重、会话独占互斥）。

## Blocked by

02（AgentReplMain 瘦身依赖 REPL 已迁 CliPlugin）

## Status
ready-for-agent

## Checklist
- [ ] DuoMain：Boot.from（默认 agent-demo.yml）+ CountDownLatch 保活 + shutdown hook 级联 dispose
- [ ] AgentReplMain 瘦身：main 委托 DuoMain；REPL/装配/会话管理代码移除；AgentReplMainTest 迁移与保留边界清晰
- [ ] agent-demo.yml 启用 cli 行（行序决定回答者路由——文档标注）；example pom 增加 duo-harness-cli 依赖
- [ ] 实测：双开启动后 CLI 与 Web 各持会话锁、互拒正确（/exit 后 CLI 锁释放、Web 不受影响）
- [ ] 全量回归绿（Web 面零回归）
